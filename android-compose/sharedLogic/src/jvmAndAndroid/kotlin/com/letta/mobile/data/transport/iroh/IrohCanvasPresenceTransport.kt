package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.canvas.CanvasPresence
import com.letta.mobile.data.canvas.CanvasPresenceTransport
import com.letta.mobile.data.canvas.InMemoryCanvasPresenceTransport
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
    /** Null in the hello a dialing peer sends so the other side accepts its stream at once. */
    val presence: CanvasPresence? = null,
)

/**
 * Live Iroh-backed transport for ephemeral peer presence and cursor telemetry.
 *
 * Transmits [CanvasPresence] state over QUIC streams using ALPN `meridian/canvas-presence/1`.
 * Operates independently from document ops, with automatic 10-second TTL expiry and
 * periodic reaper cleanup.
 *
 * A [relay] (the host every client dials) passes each presence update on to every other
 * connected peer, so clients see each other's cursors without connecting to one another.
 */
class IrohCanvasPresenceTransport(
    private val scope: CoroutineScope,
    private val endpoint: Endpoint? = null,
    private val ttlMs: Long = 10_000L,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val fallback: CanvasPresenceTransport = InMemoryCanvasPresenceTransport(ttlMs, clock),
    private val relay: Boolean = false,
) : CanvasPresenceTransport {

    companion object {
        val CANVAS_PRESENCE_ALPN = "meridian/canvas-presence/1".encodeToByteArray()
        private const val PREFIX_BYTES = 4
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
            runCanvasAcceptLoop(ep, "CanvasPresence") { handleIncomingConnection(it) }
        }
        acceptJob = job
        return job
    }

    private suspend fun handleIncomingConnection(incoming: Incoming) {
        runCatching {
            val accepting = incoming.accept()
            val peerAlpn = accepting.alpn()
            if (peerAlpn.contentEquals(CANVAS_PRESENCE_ALPN)) {
                val connection = accepting.connect()
                Telemetry.event("CanvasPresence", "incoming.connected")
                registerConnection(connection, isInbound = true)
            }
        }.onFailure { t ->
            val errorMsg = t.message ?: t.toString()
            Telemetry.event("CanvasPresence", "incoming.error", "error" to errorMsg)
        }
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

    suspend fun connectToPeer(endpointAddr: EndpointAddr): Connection {
        val ep = endpoint ?: error("Iroh endpoint is not configured")
        Telemetry.event("CanvasPresence", "dial.start")
        val connection = ep.connect(endpointAddr, CANVAS_PRESENCE_ALPN)
        Telemetry.event("CanvasPresence", "dial.connected")
        registerConnection(connection, isInbound = false)
        return connection
    }

    suspend fun connectToPeerByTicket(ticketString: String): Connection {
        val ticket = EndpointTicket.fromString(ticketString)
        return connectToPeer(ticket.endpointAddr())
    }

    fun registerConnection(connection: Connection, isInbound: Boolean = false): Job = scope.launch {
        try {
            val biStream = if (isInbound) connection.acceptBi() else connection.openBi()
            val sendStream = biStream.send()
            val recvStream = biStream.recv()

            mutex.withLock {
                activeSendStreams.add(sendStream)
                // A stream the peer cannot see until it carries data: say hello straight away.
                if (!isInbound) {
                    runCatching { sendStream.write(encodeFrame(json.encodeToString(CanvasPresenceWirePacket(canvasId = "")).encodeToByteArray())) }
                }
            }

            try {
                consumePackets(recvStream, sendStream)
            } finally {
                removeActiveSendStream(sendStream)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            val errorMsg = t.message ?: t.toString()
            Telemetry.event("CanvasPresence", "connection.error", "error" to errorMsg)
        }
    }

    private suspend fun consumePackets(recvStream: RecvStream, sendStream: SendStream) {
        while (true) {
            val frameBytes = readFrame(recvStream) ?: break
            dispatchIncomingPacket(frameBytes, sendStream)
        }
    }

    private suspend fun dispatchIncomingPacket(frameBytes: ByteArray, sendStream: SendStream) {
        val packet = runCatching {
            json.decodeFromString<CanvasPresenceWirePacket>(frameBytes.decodeToString())
        }.getOrNull() ?: return
        val presence = packet.presence ?: return // A hello.

        val canvasId = CanvasId(packet.canvasId)
        if (relay) {
            broadcast(encodeFrame(frameBytes), except = sendStream)
            return
        }
        applyPresenceInternal(canvasId, presence)
        fallback.updatePresence(canvasId, presence)
    }

    private suspend fun broadcast(frame: ByteArray, except: SendStream? = null) {
        mutex.withLock {
            val iterator = activeSendStreams.iterator()
            while (iterator.hasNext()) {
                val stream = iterator.next()
                if (stream === except) continue
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

    private suspend fun removeActiveSendStream(sendStream: SendStream) {
        withContext(NonCancellable) {
            mutex.withLock {
                activeSendStreams.remove(sendStream)
            }
            runCatching { sendStream.finish() }
        }
    }

    override suspend fun updatePresence(canvasId: CanvasId, presence: CanvasPresence) {
        applyPresenceInternal(canvasId, presence)
        fallback.updatePresence(canvasId, presence)

        val packet = CanvasPresenceWirePacket(canvasId = canvasId.value, presence = presence)
        broadcast(encodeFrame(json.encodeToString(packet).encodeToByteArray()))
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

    private fun encodeFrame(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Canvas presence frame too large: ${payload.size}" }
        val frame = ByteArray(PREFIX_BYTES + payload.size)
        frame[0] = (payload.size ushr 24).toByte()
        frame[1] = (payload.size ushr 16).toByte()
        frame[2] = (payload.size ushr 8).toByte()
        frame[3] = payload.size.toByte()
        payload.copyInto(frame, destinationOffset = PREFIX_BYTES)
        return frame
    }

    private suspend fun readFrame(stream: RecvStream): ByteArray? {
        val prefix = ByteArray(PREFIX_BYTES)
        var offset = 0
        while (offset < PREFIX_BYTES) {
            val chunk = stream.read((PREFIX_BYTES - offset).toUInt())
            if (chunk.isEmpty()) return null
            chunk.copyInto(prefix, destinationOffset = offset)
            offset += chunk.size
        }
        val length = decodePrefixLength(prefix)
        if (length !in 0..MAX_PAYLOAD_BYTES) return null
        return stream.readExact(length.toUInt())
    }

    private fun decodePrefixLength(prefix: ByteArray): Int {
        return ((prefix[0].toInt() and 0xff) shl 24) or
            ((prefix[1].toInt() and 0xff) shl 16) or
            ((prefix[2].toInt() and 0xff) shl 8) or
            (prefix[3].toInt() and 0xff)
    }
}

internal suspend fun CoroutineScope.runCanvasAcceptLoop(
    endpoint: Endpoint,
    telemetryTag: String,
    handle: suspend (Incoming) -> Unit,
) {
    while (isActive) {
        try {
            val incoming = endpoint.acceptNext() ?: return
            launch { handle(incoming) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val errorMsg = t.message ?: t.toString()
            Telemetry.event(telemetryTag, "accept.error", "error" to errorMsg)
        }
    }
}
