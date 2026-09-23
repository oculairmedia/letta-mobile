package com.letta.mobile.data.canvas

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The host side of shared canvases: every app connected to this host opens its canvases here, and
 * this binds one canvas per topic, logs every op durably in [store] before acknowledging it, catches
 * each app up, fans ops out to the other apps on the topic and relays their cursors.
 *
 * Identity is the connection's, never the payload's. Each [connect] carries the origin the host
 * authenticated (the peer's Iroh node id): it is stamped on every op the others receive and on
 * every cursor, so no app can speak as another.
 *
 * Transport-free, so the relay's rules are tested without QUIC; the Iroh adapter feeds it frames.
 */
class CanvasRelayHost(
    private val store: CanvasRelayStore,
    private val hostId: () -> String,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val presenceTtlMs: Long = PRESENCE_TTL_MS,
) {
    private val registry = Mutex()
    private val topics = mutableMapOf<String, Topic>()
    private val live = mutableSetOf<Session>()
    private var nextGeneration = 0L

    /** One topic's subscribers; its lock orders appends, catch-up and fan-out into one sequence. */
    private class Topic {
        val lock = Mutex()
        val sessions = linkedSetOf<Session>()
    }

    private suspend fun topic(name: String): Topic = registry.withLock { topics.getOrPut(name) { Topic() } }

    /**
     * A new app connection from [origin] (authenticated by the caller). [send] delivers a message
     * to that app, in order; a send that throws drops the connection.
     */
    suspend fun connect(origin: String, send: suspend (CanvasRelayMessage) -> Unit): Session {
        return registry.withLock { Session(origin, ++nextGeneration, send).also { live += it } }
    }

    inner class Session internal constructor(
        val origin: String,
        val generation: Long,
        private val send: suspend (CanvasRelayMessage) -> Unit,
    ) {
        private val joined = mutableSetOf<String>()
        /** This connection's live cursors: topic -> stamped peer id -> last seen. */
        private val presence = mutableMapOf<String, MutableMap<String, Long>>()
        private val state = Mutex()
        var closed: Boolean = false
            private set

        suspend fun receive(message: CanvasRelayMessage) {
            if (closed) return
            when (message) {
                is CanvasRelayMessage.Join -> join(message)
                is CanvasRelayMessage.Publish -> publish(message)
                is CanvasRelayMessage.Leave -> leave(message.topic)
                is CanvasRelayMessage.Presence -> relayPresence(message)
                // Host-to-app messages coming the other way: this is not an app speaking the protocol.
                else -> refuse("unexpected ${message::class.simpleName} from an app")
            }
        }

        private suspend fun join(message: CanvasRelayMessage.Join) {
            if (message.topic.isBlank() || message.proposedCanvasId.isBlank()) return refuse("empty topic or canvas id")
            val canvasId = store.bind(message.topic, CanvasId(message.proposedCanvasId))
            val topic = topic(message.topic)
            topic.lock.withLock {
                // Under the topic lock, so no op fans out to this app between its catch-up and its
                // subscription: it receives the log in cursor order, then everything after.
                deliver(CanvasRelayMessage.Joined(message.topic, canvasId.value, hostId(), store.head(message.topic)))
                var cursor = message.afterCursor.coerceAtLeast(0L)
                while (true) {
                    val page = store.readAfter(message.topic, cursor)
                    if (page.isEmpty()) break
                    for (entry in page) {
                        deliver(CanvasRelayMessage.Op(message.topic, entry.cursor, entry.origin, entry.op))
                        cursor = entry.cursor
                    }
                }
                deliver(CanvasRelayMessage.CaughtUp(message.topic, maxOf(cursor, store.head(message.topic))))
                if (!closed) {
                    topic.sessions += this
                    state.withLock { joined += message.topic }
                }
            }
        }

        private suspend fun publish(message: CanvasRelayMessage.Publish) {
            val op = message.op
            if (!state.withLock { message.topic in joined }) {
                return deliver(CanvasRelayMessage.Rejected(message.topic, op.opId, "not joined"))
            }
            if (op.opId.isBlank()) return deliver(CanvasRelayMessage.Rejected(message.topic, op.opId, "no op id"))
            val topic = topic(message.topic)
            topic.lock.withLock {
                val appended = store.append(message.topic, op, origin)
                // Durable first, then acknowledged: an ack is a promise the op survives a host restart.
                deliver(CanvasRelayMessage.Ack(message.topic, op.opId, appended.cursor, appended.duplicate))
                if (!appended.duplicate) {
                    val fanned = CanvasRelayMessage.Op(message.topic, appended.cursor, origin, op)
                    topic.sessions.filter { it !== this }.forEach { it.deliver(fanned) }
                }
            }
        }

        private suspend fun relayPresence(message: CanvasRelayMessage.Presence) {
            if (!state.withLock { message.topic in joined }) return
            // Scoped by the authenticated origin: whatever peer id the app claims, it can only name
            // cursors of its own, never another device's.
            val stamped = message.presence.copy(
                peerId = "$origin/${message.presence.peerId}",
                lastActiveEpochMs = clock(),
            )
            state.withLock {
                val mine = presence.getOrPut(message.topic) { mutableMapOf() }
                if (stamped.isActive) mine[stamped.peerId] = stamped.lastActiveEpochMs else mine.remove(stamped.peerId)
            }
            val relayed = CanvasRelayMessage.PresenceRelayed(message.topic, stamped)
            val topic = topic(message.topic)
            topic.lock.withLock { topic.sessions.filter { it !== this }.forEach { it.deliver(relayed) } }
        }

        private suspend fun leave(name: String) {
            val topic = topic(name)
            val gone = state.withLock {
                joined -= name
                presence.remove(name)?.keys.orEmpty()
            }
            topic.lock.withLock {
                topic.sessions -= this
                announceGone(topic, name, gone)
            }
        }

        /** The connection ended: it leaves every topic and its cursors disappear for everyone else. */
        suspend fun close() {
            if (closed && registry.withLock { this !in live }) return
            closed = true
            registry.withLock { live -= this }
            val names = state.withLock { joined.toList() }
            for (name in names) leave(name)
        }

        /** Cursors this connection has not refreshed within the TTL. */
        internal suspend fun reapPresence(now: Long) {
            val expired = state.withLock {
                presence.mapValues { (_, peers) ->
                    peers.filterValues { now - it > presenceTtlMs }.keys.also { stale -> stale.forEach { peers.remove(it) } }
                }.filterValues { it.isNotEmpty() }
            }
            for ((name, peers) in expired) {
                val topic = topic(name)
                topic.lock.withLock { announceGone(topic, name, peers) }
            }
        }

        private suspend fun announceGone(topic: Topic, name: String, peers: Collection<String>) {
            for (peerId in peers) {
                val gone = CanvasRelayMessage.PresenceGone(name, peerId)
                topic.sessions.filter { it !== this }.forEach { it.deliver(gone) }
            }
        }

        private suspend fun refuse(reason: String) {
            deliver(CanvasRelayMessage.Refused(reason))
            close()
        }

        internal suspend fun deliver(message: CanvasRelayMessage) {
            if (closed) return
            try {
                send(message)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // An app that cannot take a message is gone; it reconnects and catches up.
                closed = true
            }
        }
    }

    /** Expires cursors not refreshed within the TTL, on every connection. Run periodically. */
    suspend fun reapPresence(now: Long = clock()) {
        registry.withLock { live.toList() }.forEach { it.reapPresence(now) }
    }

    companion object {
        const val PRESENCE_TTL_MS: Long = 10_000L
    }
}
