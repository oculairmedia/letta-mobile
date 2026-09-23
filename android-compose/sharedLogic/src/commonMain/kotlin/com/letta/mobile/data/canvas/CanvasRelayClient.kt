package com.letta.mobile.data.canvas

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One connection to the host, as [CanvasRelayClient] drives it. */
interface CanvasRelayConnection {
    /** The host's identity (its Iroh node id), which scopes this app's cursors into its logs. */
    val hostId: String

    suspend fun send(message: CanvasRelayMessage)

    /** The next message from the host, or null once the connection has ended. */
    suspend fun receive(): CanvasRelayMessage?
}

/**
 * An app's side of shared canvases: the [CanvasSyncTransport] and [CanvasPresenceTransport] every
 * canvas session in the app uses. Transport-free; the Iroh adapter feeds it one
 * [CanvasRelayConnection] at a time ([run]).
 *
 * Delivery is local-first and acknowledged. A session puts an edit in the local op log before
 * publishing it; publishing puts its id in the durable queue ([delivery]) before anything touches
 * the network, and only the host's ack takes it out. Each connection is a new generation: on it
 * the app joins its canvases' topics, uploads everything queued, and applies the host's catch-up,
 * recording its cursor only after each op is applied. A canvas reports [CanvasSyncHealth.Synced]
 * only once, on the current connection, its queue is empty and catch-up is complete; a message
 * arriving on an older connection changes nothing.
 *
 * Sessions of one canvas in this app share edits and cursors directly, connected or not.
 */
class CanvasRelayClient(
    private val opLog: CanvasOpLog,
    private val delivery: CanvasDeliveryStore,
    /** The topic [CanvasId] syncs under: its conversation's, or its own for a standalone canvas. */
    private val topicOf: suspend (CanvasId) -> String,
    private val presenceTtlMs: Long = CanvasRelayHost.PRESENCE_TTL_MS,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : CanvasSyncTransport, CanvasPresenceTransport {

    private val lock = Mutex()
    private val canvases = mutableMapOf<CanvasId, Canvas>()
    private var current: Live? = null
    private var generation = 0L
    private var hostExpected = false
    private var refusal: String? = null

    private val localPresence = InMemoryCanvasPresenceTransport(presenceTtlMs, clock)
    private val remotePresence = InMemoryCanvasPresenceTransport(presenceTtlMs, clock)

    private val healthFlows = MutableStateFlow<Map<CanvasId, MutableStateFlow<CanvasSyncHealth>>>(emptyMap())

    private fun healthFlow(canvasId: CanvasId): MutableStateFlow<CanvasSyncHealth> =
        healthFlows.getOrCreate(canvasId) { MutableStateFlow(CanvasSyncHealth.LocalOnly(NO_HOST)) }

    private inner class Canvas(val id: CanvasId, val topic: String) {
        val appliers = mutableListOf<suspend (CanvasOp) -> Unit>()
        val health: MutableStateFlow<CanvasSyncHealth> = healthFlow(id)

        /**
         * This app's own edits for the sessions of this canvas here. Buffered, never handed over in
         * [publish]: a session publishes while holding its own lock, so applying in the same call
         * would wait on that lock forever.
         */
        val local = MutableSharedFlow<CanvasOp>(extraBufferCapacity = Int.MAX_VALUE)
        var canonicalId: CanvasId? = null
        var hostCursor: Long = 0L
    }

    private class Live(val generation: Long, val connection: CanvasRelayConnection) {
        val joinSent = mutableSetOf<String>()
        val caughtUp = mutableSetOf<String>()
        val inFlight = mutableSetOf<String>()
    }

    /** What this app knows of a canvas on its host, for settle comparisons and diagnostics. */
    data class RelayView(val hostId: String?, val topic: String, val canonicalId: CanvasId?, val hostCursor: Long, val queued: Int)

    // ---- Host availability ----

    /**
     * Whether this app is meant to reach a host (it is on an Iroh backend). Without one every canvas
     * is [CanvasSyncHealth.LocalOnly]; with one that is out of reach, [CanvasSyncHealth.OfflineQueued].
     */
    suspend fun expectHost(expected: Boolean) {
        lock.withLock { hostExpected = expected }
        refreshAll()
    }

    // ---- CanvasSyncTransport ----

    override suspend fun publish(canvasId: CanvasId, op: CanvasOp) {
        val canvas = canvas(canvasId)
        // Durable before the network: a publish that fails leaves the op queued, not lost (I3).
        delivery.enqueue(canvas.topic, listOf(op.opId))
        lock.withLock { canvasesOf(canvas.topic) }.forEach { it.local.tryEmit(op) }
        val live = lock.withLock { current?.takeIf { canvas.topic in it.joinSent }?.also { it.inFlight += op.opId } }
        if (live != null) {
            try {
                live.connection.send(CanvasRelayMessage.Publish(canvas.topic, op))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                lock.withLock { live.inFlight -= op.opId }
            }
        }
        refresh(canvas.topic)
    }

    override fun subscribe(canvasId: CanvasId): Flow<CanvasOp> = channelFlow {
        launch { deliverTo(canvasId) { send(it) } }
        awaitClose()
    }

    override suspend fun deliverTo(canvasId: CanvasId, apply: suspend (CanvasOp) -> Unit) {
        val canvas = canvas(canvasId)
        lock.withLock { canvas.appliers += apply }
        try {
            coroutineScope {
                launch { canvas.local.collect { apply(it) } }
                val live = lock.withLock { current }
                // A connection that fails here ends in [run]; this session keeps working locally.
                if (live != null) runCatching { join(live, canvas.topic) }.onFailure { if (it is CancellationException) throw it }
                refresh(canvas.topic)
                awaitCancellation()
            }
        } finally {
            lock.withLock { canvas.appliers -= apply }
        }
    }

    /** Kept current once the canvas is open here ([deliverTo]); until then it says what it can. */
    override fun health(canvasId: CanvasId): StateFlow<CanvasSyncHealth> = healthFlow(canvasId).asStateFlow()

    suspend fun relayView(canvasId: CanvasId): RelayView {
        val canvas = canvas(canvasId)
        val live = lock.withLock { current }
        return RelayView(live?.connection?.hostId, canvas.topic, canvas.canonicalId, canvas.hostCursor, delivery.queued(canvas.topic).size)
    }

    // ---- CanvasPresenceTransport ----

    override suspend fun updatePresence(canvasId: CanvasId, presence: CanvasPresence) {
        localPresence.updatePresence(canvasId, presence)
        val canvas = canvas(canvasId)
        val live = lock.withLock { current?.takeIf { canvas.topic in it.joinSent } } ?: return
        runCatching { live.connection.send(CanvasRelayMessage.Presence(canvas.topic, presence)) }
            .onFailure { if (it is CancellationException) throw it }
    }

    override fun observePresence(canvasId: CanvasId): Flow<List<CanvasPresence>> =
        combine(localPresence.observePresence(canvasId), remotePresence.observePresence(canvasId)) { local, remote -> local + remote }

    // ---- The connection ----

    /**
     * Serves [connection] until it ends: joins every open canvas, uploads the queue, applies
     * catch-up and live ops. Returns when the host closes it, refuses it, or it fails.
     */
    suspend fun run(connection: CanvasRelayConnection) {
        val live = lock.withLock {
            Live(++generation, connection).also {
                current = it
                hostExpected = true
            }
        }
        try {
            refreshAll()
            val topics = lock.withLock { canvases.values.filter { it.appliers.isNotEmpty() }.map { it.topic }.toSet() }
            topics.forEach { join(live, it) }
            while (true) {
                val message = connection.receive() ?: break
                if (!handle(live, message)) break
            }
        } finally {
            lock.withLock { if (current === live) current = null }
            refreshAll()
        }
    }

    private suspend fun join(live: Live, topic: String) {
        val send = lock.withLock { current === live && live.joinSent.add(topic) }
        if (!send) return
        val canvas = lock.withLock { canvases.values.first { it.topic == topic } }
        val after = delivery.cursor(live.connection.hostId, topic) ?: 0L
        live.connection.send(CanvasRelayMessage.Join(topic, canvas.id.value, after))
    }

    /** False when the connection must end. Messages from a superseded connection change nothing (I6). */
    private suspend fun handle(live: Live, message: CanvasRelayMessage): Boolean {
        if (lock.withLock { current !== live }) return false
        when (message) {
            is CanvasRelayMessage.Joined -> joined(live, message)
            is CanvasRelayMessage.Op -> {
                applyLocally(message.topic, message.op)
                // Only after every session applied it: a crash before this replays it, never skips it.
                delivery.advanceCursor(live.connection.hostId, message.topic, message.cursor)
                lock.withLock { canvasesOf(message.topic).forEach { it.hostCursor = maxOf(it.hostCursor, message.cursor) } }
            }
            is CanvasRelayMessage.CaughtUp -> {
                delivery.advanceCursor(live.connection.hostId, message.topic, message.cursor)
                lock.withLock {
                    live.caughtUp += message.topic
                    canvasesOf(message.topic).forEach { it.hostCursor = maxOf(it.hostCursor, message.cursor) }
                }
                refresh(message.topic)
            }
            is CanvasRelayMessage.Ack -> {
                val mine = lock.withLock { live.inFlight.remove(message.opId) }
                if (mine) {
                    delivery.acknowledge(message.topic, message.opId)
                    // The host acks in log order, after every earlier op it fanned out to this app, so
                    // the log is applied here up to this op too.
                    delivery.advanceCursor(live.connection.hostId, message.topic, message.cursor)
                    lock.withLock { canvasesOf(message.topic).forEach { it.hostCursor = maxOf(it.hostCursor, message.cursor) } }
                }
                refresh(message.topic)
            }
            is CanvasRelayMessage.Rejected -> {
                lock.withLock { live.inFlight -= message.opId }
                delivery.reject(message.topic, message.opId, message.reason)
                refresh(message.topic)
            }
            is CanvasRelayMessage.PresenceRelayed ->
                lock.withLock { canvasesOf(message.topic).map { it.id } }.forEach { remotePresence.updatePresence(it, message.presence) }
            is CanvasRelayMessage.PresenceGone -> {
                val gone = CanvasPresence(message.peerId, "", "", 0f, 0f, isActive = false)
                lock.withLock { canvasesOf(message.topic).map { it.id } }.forEach { remotePresence.updatePresence(it, gone) }
            }
            is CanvasRelayMessage.Refused -> {
                lock.withLock { refusal = message.reason }
                return false
            }
            // Replies about assets (w3nb2.3): this client does not ask for them yet, but a host that
            // sends one is speaking the protocol, not breaking it.
            is CanvasRelayMessage.AssetStored, is CanvasRelayMessage.AssetData,
            is CanvasRelayMessage.AssetMissing, is CanvasRelayMessage.AssetRejected -> Unit
            // App-to-host messages coming this way: not a host speaking the protocol.
            else -> {
                lock.withLock { refusal = "the host sent ${message::class.simpleName}" }
                return false
            }
        }
        return true
    }

    private suspend fun joined(live: Live, message: CanvasRelayMessage.Joined) {
        val firstTimeHere = delivery.cursor(live.connection.hostId, message.topic) == null
        val locals = lock.withLock {
            refusal = null
            canvasesOf(message.topic).onEach { it.canonicalId = CanvasId(message.canvasId) }.map { it.id }
        }
        val localOps = locals.flatMap { opLog.getOps(it, 0L) }.associateBy { it.opId }
        if (firstTimeHere) {
            // First time on this host: everything this app has for the canvas goes up, which is how
            // canvases made before sharing - or on another host - arrive. Idempotent on the host.
            delivery.enqueue(message.topic, localOps.values.sortedWith(CanvasOpOrder).map { it.opId })
            delivery.advanceCursor(live.connection.hostId, message.topic, 0L)
        }
        for (opId in delivery.queued(message.topic)) {
            val op = localOps[opId]
            if (op == null) {
                // Queued but gone from the local log: nothing left to send.
                delivery.reject(message.topic, opId, "not in the local op log")
                continue
            }
            val send = lock.withLock { (current === live).also { if (it) live.inFlight += opId } }
            if (!send) return
            live.connection.send(CanvasRelayMessage.Publish(message.topic, op))
        }
        refresh(message.topic)
    }

    // ---- Local ----

    private suspend fun canvas(canvasId: CanvasId): Canvas {
        lock.withLock { canvases[canvasId] }?.let { return it }
        val topic = topicOf(canvasId)
        return lock.withLock { canvases.getOrPut(canvasId) { Canvas(canvasId, topic) } }
    }

    private fun canvasesOf(topic: String): List<Canvas> = canvases.values.filter { it.topic == topic }

    /** Every session of the canvases of [topic] in this app applies [op]; they skip ops they have. */
    private suspend fun applyLocally(topic: String, op: CanvasOp) {
        val appliers = lock.withLock { canvasesOf(topic).flatMap { it.appliers.toList() } }
        for (apply in appliers) apply(op)
    }

    private suspend fun refreshAll() {
        val topics = lock.withLock { canvases.values.map { it.topic }.toSet() }
        topics.forEach { refresh(it) }
    }

    private suspend fun refresh(topic: String) {
        val queued = delivery.queued(topic).size
        lock.withLock {
            val live = current
            val health = when {
                refusal != null -> CanvasSyncHealth.Failed(refusal!!)
                !hostExpected -> CanvasSyncHealth.LocalOnly(NO_HOST)
                live == null -> CanvasSyncHealth.OfflineQueued(queued)
                // Synced only past the barrier: every queued op acknowledged and catch-up complete (I7).
                topic !in live.caughtUp || queued > 0 -> CanvasSyncHealth.Connecting
                else -> CanvasSyncHealth.Synced
            }
            canvasesOf(topic).forEach { it.health.value = health }
        }
    }

    private companion object {
        const val NO_HOST = "Not connected to an Iroh host; this canvas stays on this device"
    }
}
