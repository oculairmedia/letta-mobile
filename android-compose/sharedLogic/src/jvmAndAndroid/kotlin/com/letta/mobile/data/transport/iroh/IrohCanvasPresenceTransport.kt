package com.letta.mobile.data.transport.iroh

import com.letta.mobile.util.attr
import com.letta.mobile.data.canvas.CanvasPresence
import com.letta.mobile.data.canvas.CanvasPresenceTransport
import com.letta.mobile.data.canvas.InMemoryCanvasPresenceTransport
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.util.Telemetry
import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointTicket
import computer.iroh.Incoming
import computer.iroh.RecvStream
import computer.iroh.SendStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Wire packet for peer presence telemetry over dedicated ALPN `meridian/canvas-presence/1`.
 */
@Serializable
data class CanvasPresenceWirePacket(
    val canvasId: String,
    val presence: CanvasPresence,
)

/**
 * Live Iroh-backed transport for ephemeral peer presence and cursor telemetry.
 *
 * Transmits [CanvasPresence] state over QUIC streams using ALPN `meridian/canvas-presence/1`.
 * Operates independently from document ops, with automatic 10-second TTL expiry and
 * periodic reaper cleanup.
 */
class IrohCanvasPresenceTransport(
    private val scope: CoroutineScope,
    private val endpoint: Endpoint? = null,
    private val ttlMs: Long = 10_000L,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val fallback: CanvasPresenceTransport = InMemoryCanvasPresenceTransport(ttlMs, clock),
) : CanvasPresenceTransport {

    companion object {
        val CANVAS_PRESENCE_ALPN = "meridian/canvas-presence/1".encodeToByteArray()
        private const val MAX_PAYLOAD_BYTES = 1024 * 1024 // 1MB
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val activeSendStreams = mutableListOf<SendStream>()
    private val presencesByCanvas = mutableMapOf<CanvasId, MutableMap<String, CanvasPresence>>()
    private val flowsByCanvas = mutableMapOf<CanvasId, MutableStateFlow<List<CanvasPresence>>>()
    private var acceptJob: Job? = null
    private var reaperJob: Job? = null

    init {
        if (endpoint != null) {
            startAcceptLoop()
        }
        startReaper()
    }

    fun startAcceptLoop(): Job {
        acceptJob?.cancel()
        val ep = endpoint ?: return Job().apply { complete() }
        val job = scope.launch {
            runCanvasAcceptLoop(ep, CANVAS_PRESENCE_ALPN, "CanvasPresence") { registerConnection(it, isInbound = true) }
        }
        acceptJob = job
        return job
    }


    private fun startReaper(): Job {
        reaperJob?.cancel()
        val job = scope.launch {
            while (isActive) {
                delay(2_000L)
                reapExpiredPresences()
            }
        }
        reaperJob = job
        return job
    }

    private suspend fun reapExpiredPresences() = mutex.withLock {
        val now = clock()
        for ((canvasId, map) in presencesByCanvas) {
            if (pruneExpired(map, now)) {
                flowsByCanvas[canvasId]?.value = map.values.toList()
            }
        }
    }

    private fun pruneExpired(map: MutableMap<String, CanvasPresence>, now: Long): Boolean {
        val iterator = map.entries.iterator()
        var changed = false
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastActiveEpochMs > ttlMs) {
                iterator.remove()
                changed = true
            }
        }
        return changed
    }

    suspend fun connectToPeer(endpointAddr: EndpointAddr): Connection =
        dialCanvasPeer(endpoint, endpointAddr, CANVAS_PRESENCE_ALPN, "CanvasPresence")
            .also { registerConnection(it, isInbound = false) }

    suspend fun connectToPeerByTicket(ticketString: String): Connection =
        connectToPeer(EndpointTicket.fromString(ticketString).endpointAddr())

    fun registerConnection(connection: Connection, isInbound: Boolean = false): Job = scope.launch {
        mutex.runCanvasStream(
            { connection.canvasBiStream(isInbound) },
            activeSendStreams,
            "CanvasPresence",
        ) { recv, _ ->
            consumePackets(recv)
        }
    }

    private suspend fun consumePackets(recvStream: RecvStream) {
        while (true) {
            val frameBytes = readCanvasFrame(recvStream, MAX_PAYLOAD_BYTES) ?: break
            dispatchIncomingPacket(frameBytes)
        }
    }

    private suspend fun dispatchIncomingPacket(frameBytes: ByteArray) {
        val packet = runCatching {
            json.decodeFromString<CanvasPresenceWirePacket>(frameBytes.decodeToString())
        }.getOrNull() ?: return

        val canvasId = CanvasId(packet.canvasId)
        applyPresenceInternal(canvasId, packet.presence)
        fallback.updatePresence(canvasId, packet.presence)
    }

    override suspend fun updatePresence(canvasId: CanvasId, presence: CanvasPresence) {
        applyPresenceInternal(canvasId, presence)
        fallback.updatePresence(canvasId, presence)

        val packet = CanvasPresenceWirePacket(canvasId = canvasId.value, presence = presence)
        val payload = json.encodeToString(packet).encodeToByteArray()
        val frame = encodeCanvasFrame(payload, MAX_PAYLOAD_BYTES, "Canvas presence")

        mutex.withLock {
            val iterator = activeSendStreams.iterator()
            while (iterator.hasNext()) {
                val stream = iterator.next()
                try {
                    stream.write(frame)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    iterator.remove()
                }
            }
        }
    }

    override fun observePresence(canvasId: CanvasId): Flow<List<CanvasPresence>> {
        return synchronized(flowsByCanvas) {
            flowsByCanvas.getOrPut(canvasId) {
                MutableStateFlow(emptyList())
            }.asStateFlow()
        }
    }

    private suspend fun applyPresenceInternal(canvasId: CanvasId, presence: CanvasPresence) = mutex.withLock {
        val map = presencesByCanvas.getOrPut(canvasId) { mutableMapOf() }
        val now = clock()
        if (!presence.isActive) {
            map.remove(presence.peerId)
        } else {
            map[presence.peerId] = presence.copy(lastActiveEpochMs = now)
        }

        pruneExpired(map, now)

        val list = map.values.toList()
        val flow = synchronized(flowsByCanvas) {
            flowsByCanvas.getOrPut(canvasId) { MutableStateFlow(emptyList()) }
        }
        flow.value = list
    }

}

/** Dials [endpointAddr] on [alpn] through [endpoint], reporting the dial under [telemetryTag]. */
internal suspend fun dialCanvasPeer(
    endpoint: Endpoint?,
    endpointAddr: EndpointAddr,
    alpn: ByteArray,
    telemetryTag: String,
): Connection {
    val ep = endpoint ?: error("Iroh endpoint is not configured")
    Telemetry.event(telemetryTag, "dial.start")
    val connection = ep.connect(endpointAddr, alpn)
    Telemetry.event(telemetryTag, "dial.connected")
    return connection
}

/**
 * Accepts [this] incoming connection when its ALPN is [alpn] and hands it to [register]; any other
 * ALPN is ignored. Failures are reported under [telemetryTag] and never escape the accept loop.
 */
private suspend fun Incoming.acceptCanvasConnection(
    alpn: ByteArray,
    telemetryTag: String,
    register: (Connection) -> Unit,
) {
    runCatching {
        val accepting = accept()
        if (accepting.alpn().contentEquals(alpn)) {
            val connection = accepting.connect()
            Telemetry.event(telemetryTag, "incoming.connected")
            register(connection)
        }
    }.onFailure { t ->
        Telemetry.event(telemetryTag, "incoming.error", "error" attr (t.message ?: t.toString()))
    }
}

/** Accepts the peer's canvas stream when it dialled us, otherwise opens one to it. */
internal suspend fun Connection.canvasBiStream(isInbound: Boolean): BiStream =
    if (isInbound) acceptBi() else openBi()

/**
 * Runs one canvas bidirectional stream from [openStream], registering its send side in
 * [activeSendStreams] (guarded by this mutex) for the stream's lifetime. Errors other than
 * cancellation are reported under [telemetryTag] and end only this stream.
 */
internal suspend fun Mutex.runCanvasStream(
    openStream: suspend () -> BiStream,
    activeSendStreams: MutableList<SendStream>,
    telemetryTag: String,
    consume: suspend (RecvStream, SendStream) -> Unit,
) {
    try {
        val biStream = openStream()
        val sendStream = biStream.send()
        val recvStream = biStream.recv()
        withLock { activeSendStreams.add(sendStream) }
        try {
            consume(recvStream, sendStream)
        } finally {
            withContext(NonCancellable) {
                withLock { activeSendStreams.remove(sendStream) }
                runCatching { sendStream.finish() }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        Telemetry.event(telemetryTag, "connection.error", "error" attr (t.message ?: t.toString()))
    }
}

private const val CANVAS_PREFIX_BYTES = 4

internal fun encodeCanvasFrame(payload: ByteArray, maxPayloadBytes: Int, description: String): ByteArray {
    require(payload.size <= maxPayloadBytes) { "$description frame too large: ${payload.size}" }
    val frame = ByteArray(CANVAS_PREFIX_BYTES + payload.size)
    frame[0] = (payload.size ushr 24).toByte()
    frame[1] = (payload.size ushr 16).toByte()
    frame[2] = (payload.size ushr 8).toByte()
    frame[3] = payload.size.toByte()
    payload.copyInto(frame, destinationOffset = CANVAS_PREFIX_BYTES)
    return frame
}

internal suspend fun readCanvasFrame(stream: RecvStream, maxPayloadBytes: Int): ByteArray? {
    val prefix = ByteArray(CANVAS_PREFIX_BYTES)
    var offset = 0
    while (offset < CANVAS_PREFIX_BYTES) {
        val chunk = stream.read((CANVAS_PREFIX_BYTES - offset).toUInt())
        if (chunk.isEmpty()) return null
        chunk.copyInto(prefix, destinationOffset = offset)
        offset += chunk.size
    }
    val length = ((prefix[0].toInt() and 0xff) shl 24) or
        ((prefix[1].toInt() and 0xff) shl 16) or
        ((prefix[2].toInt() and 0xff) shl 8) or
        (prefix[3].toInt() and 0xff)
    if (length !in 0..maxPayloadBytes) return null
    return stream.readExact(length.toUInt())
}

internal suspend fun CoroutineScope.runCanvasAcceptLoop(
    endpoint: Endpoint,
    alpn: ByteArray,
    telemetryTag: String,
    register: (Connection) -> Unit,
) {
    while (isActive) {
        try {
            val incoming = endpoint.acceptNext() ?: return
            launch { incoming.acceptCanvasConnection(alpn, telemetryTag, register) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val errorMsg = t.message ?: t.toString()
            Telemetry.event(telemetryTag, "accept.error", "error" attr errorMsg)
        }
    }
}
