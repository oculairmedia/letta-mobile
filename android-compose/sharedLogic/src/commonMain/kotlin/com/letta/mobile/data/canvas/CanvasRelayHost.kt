package com.letta.mobile.data.canvas

import com.letta.mobile.data.storage.AssetRef
import com.letta.mobile.data.storage.AssetRefs
import com.letta.mobile.data.storage.AssetStore
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
 * Large things kept out of the ops (images now, any file later) travel beside them as assets: an
 * app sends an asset's bytes before the op that refers to it, the host keeps them in [assets] by
 * their hash, and any app on the topic asks for them by ref. A request for an asset still on its
 * way waits for it.
 *
 * Transport-free, so the relay's rules are tested without QUIC; the Iroh adapter feeds it frames.
 */
class CanvasRelayHost(
    private val store: CanvasRelayStore,
    private val hostId: () -> String,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val presenceTtlMs: Long = PRESENCE_TTL_MS,
    /** Where this host keeps assets; null for a host that relays ops only (assets are refused). */
    private val assets: AssetStore? = null,
    private val maxAssetBytes: Long = MAX_ASSET_BYTES,
) {
    private val registry = Mutex()
    private val topics = mutableMapOf<String, Topic>()
    private val live = mutableSetOf<Session>()
    private var nextGeneration = 0L

    /** Assets an app is sending right now, by ref, and who is sending each (under [registry]). */
    private val arriving = mutableMapOf<String, Session>()

    /** Requests for assets still arriving: ref -> (asker, topic) (under [registry]). */
    private val waiting = mutableMapOf<String, MutableList<Pair<Session, String>>>()

    /** An asset now whole: answer everyone who asked for it while it arrived. */
    private suspend fun arrived(ref: String, asset: AssetRef?, bytes: ByteArray?) {
        val askers = registry.withLock {
            arriving.remove(ref)
            waiting.remove(ref).orEmpty()
        }
        for ((asker, topic) in askers) {
            if (asset != null && bytes != null) asker.sendAsset(topic, asset, bytes) else asker.deliver(CanvasRelayMessage.AssetMissing(topic, ref))
        }
    }

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

        /** Assets this app is part way through sending: ref -> chunks so far. */
        private val uploads = mutableMapOf<String, Upload>()
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
                is CanvasRelayMessage.AssetPut -> putAsset(message)
                is CanvasRelayMessage.AssetGet -> getAsset(message)
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

        private suspend fun putAsset(message: CanvasRelayMessage.AssetPut) {
            val ref = message.asset.ref
            val rejected = when {
                assets == null -> "this host keeps no assets"
                !state.withLock { message.topic in joined } -> "not joined"
                !AssetRefs.isValid(ref) -> "not an asset ref"
                message.asset.byteSize !in 0..maxAssetBytes -> "larger than this host keeps"
                message.count !in 1..maxChunks() || message.index !in 0 until message.count -> "not a chunk of it"
                else -> null
            }
            if (rejected != null) return deliver(CanvasRelayMessage.AssetRejected(message.topic, ref, rejected))
            val store = assets ?: return
            if (store.has(ref)) {
                // Already here: said once, at the first chunk; the rest of the upload is ignored.
                state.withLock { uploads.remove(ref) }
                if (message.index == 0) deliver(CanvasRelayMessage.AssetStored(message.topic, ref))
                return
            }
            val chunk = runCatching { kotlin.io.encoding.Base64.decode(message.data) }.getOrNull()
                ?: return deliver(CanvasRelayMessage.AssetRejected(message.topic, ref, "chunk is not base64"))
            registry.withLock { arriving.getOrPut(ref) { this } }
            val outcome = state.withLock {
                // A few at a time per app: each is held in memory until whole.
                if (ref !in uploads && uploads.size >= MAX_UPLOADS_PER_APP) {
                    Upload.Outcome.Invalid("too many assets arriving at once")
                } else {
                    uploads.getOrPut(ref) { Upload(message.asset, message.count) }.add(message.index, chunk, maxAssetBytes)
                }
            }
            when (outcome) {
                is Upload.Outcome.Partial -> Unit
                is Upload.Outcome.Invalid -> {
                    state.withLock { uploads.remove(ref) }
                    deliver(CanvasRelayMessage.AssetRejected(message.topic, ref, outcome.reason))
                    arrived(ref, null, null)
                }
                is Upload.Outcome.Complete -> {
                    state.withLock { uploads.remove(ref) }
                    // Kept only if the bytes really are the asset they claim: a store is content
                    // addressed, and an app must not get another asset served under this ref.
                    val stored = runCatching { store.put(message.asset.mediaType, outcome.bytes) }.getOrNull()
                    if (stored?.ref != ref) {
                        deliver(CanvasRelayMessage.AssetRejected(message.topic, ref, "bytes are not that asset"))
                        arrived(ref, null, null)
                    } else {
                        deliver(CanvasRelayMessage.AssetStored(message.topic, ref))
                        arrived(ref, stored, outcome.bytes)
                    }
                }
            }
        }

        private suspend fun getAsset(message: CanvasRelayMessage.AssetGet) {
            val store = assets
            if (store == null || !AssetRefs.isValid(message.ref) || !state.withLock { message.topic in joined }) {
                return deliver(CanvasRelayMessage.AssetMissing(message.topic, message.ref))
            }
            val bytes = store.get(message.ref)
            if (bytes != null) {
                val asset = AssetRef(message.ref, store.mediaType(message.ref) ?: DEFAULT_MEDIA_TYPE, bytes.size.toLong())
                return sendAsset(message.topic, asset, bytes)
            }
            val parked = registry.withLock {
                if (message.ref in arriving) {
                    waiting.getOrPut(message.ref) { mutableListOf() } += this to message.topic
                    true
                } else {
                    false
                }
            }
            if (!parked) deliver(CanvasRelayMessage.AssetMissing(message.topic, message.ref))
        }

        /** [bytes] to this app in chunks, each well inside a frame. */
        internal suspend fun sendAsset(topic: String, asset: AssetRef, bytes: ByteArray) {
            val count = ((bytes.size + ASSET_CHUNK_BYTES - 1) / ASSET_CHUNK_BYTES).coerceAtLeast(1)
            for (index in 0 until count) {
                val from = index * ASSET_CHUNK_BYTES
                val to = minOf(bytes.size, from + ASSET_CHUNK_BYTES)
                deliver(CanvasRelayMessage.AssetData(topic, asset, index, count, kotlin.io.encoding.Base64.encode(bytes, from, to)))
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
            // Assets this app was still sending will not arrive now: whoever waits for one is told.
            val abandoned = state.withLock { uploads.keys.toList().also { uploads.clear() } }
            val mine = registry.withLock { arriving.filterValues { it === this }.keys.toList() } + abandoned
            for (ref in mine.distinct()) arrived(ref, null, null)
            registry.withLock { waiting.values.forEach { askers -> askers.removeAll { it.first === this } } }
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

    private fun maxChunks(): Int = ((maxAssetBytes + ASSET_CHUNK_BYTES - 1) / ASSET_CHUNK_BYTES).toInt().coerceAtLeast(1)

    /** One asset part way through arriving: its chunks by index, until all [count] are in. */
    private class Upload(val asset: AssetRef, val count: Int) {
        private val chunks = arrayOfNulls<ByteArray>(count)
        private var received = 0
        private var size = 0L

        sealed interface Outcome {
            data object Partial : Outcome
            class Invalid(val reason: String) : Outcome
            class Complete(val bytes: ByteArray) : Outcome
        }

        fun add(index: Int, chunk: ByteArray, limit: Long): Outcome {
            if (index >= count) return Outcome.Invalid("not a chunk of it")
            if (chunks[index] == null) {
                chunks[index] = chunk
                received++
                size += chunk.size
            }
            if (size > asset.byteSize || size > limit) return Outcome.Invalid("larger than it said")
            if (received < count) return Outcome.Partial
            if (size != asset.byteSize) return Outcome.Invalid("not the size it said")
            val whole = ByteArray(size.toInt())
            var at = 0
            for (part in chunks) {
                val bytes = part ?: return Outcome.Invalid("a chunk went missing")
                bytes.copyInto(whole, at)
                at += bytes.size
            }
            return Outcome.Complete(whole)
        }
    }

    companion object {
        const val PRESENCE_TTL_MS: Long = 10_000L

        /** Raw bytes per asset chunk: about 1.4 MB as base64, far inside a relay frame (8 MB). */
        const val ASSET_CHUNK_BYTES: Int = 1024 * 1024

        /** The largest asset a host keeps unless told otherwise. */
        const val MAX_ASSET_BYTES: Long = 50L * 1024 * 1024

        private const val DEFAULT_MEDIA_TYPE = "application/octet-stream"
        private const val MAX_UPLOADS_PER_APP = 4
    }
}
