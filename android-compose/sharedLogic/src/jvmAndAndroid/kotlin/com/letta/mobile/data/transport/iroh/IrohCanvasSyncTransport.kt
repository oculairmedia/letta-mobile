package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasOpLog
import com.letta.mobile.data.canvas.CanvasSyncTransport
import com.letta.mobile.data.canvas.LoopbackCanvasSyncTransport
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Wire packet for transmitting [CanvasOp]s over the dedicated Iroh ALPN `meridian/canvas-sync/1`.
 */
@Serializable
data class CanvasOpWirePacket(
    val canvasId: String,
    val op: CanvasOp? = null,
    val requestCatchUpSinceLamport: Long? = null,
)

/**
 * Live Iroh-backed transport for multi-device canvas synchronization.
 *
 * Runs independently from App Server chat WebSocket framing, operating over
 * dedicated BiStreams with length-prefixed binary framing on ALPN `meridian/canvas-sync/1`.
 * Supports peer dialing via ticket or address, automatic accept loop, and historical op catch-up.
 *
 * Two roles:
 * - A client (the apps): ops it receives go to its local subscribers. When it dials, it greets
 *   the peer at once (a QUIC stream is invisible to the other side until it carries data) and
 *   asks for catch-up on every canvas it has open, so edits made while it was away arrive.
 * - A [relay] (the host every client already dials): it has no subscribers of its own. An op
 *   from one client is logged once in [opLog] and forwarded to every other client, and catch-up
 *   requests are answered from that log, so clients share canvases without knowing each other.
 */
class IrohCanvasSyncTransport(
    private val scope: CoroutineScope,
    private val endpoint: Endpoint? = null,
    private val opLog: CanvasOpLog? = null,
    private val fallback: CanvasSyncTransport = LoopbackCanvasSyncTransport(),
    private val relay: Boolean = false,
) : CanvasSyncTransport {

    companion object {
        val CANVAS_SYNC_ALPN = "meridian/canvas-sync/1".encodeToByteArray()
        private const val PREFIX_BYTES = 4
        private const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024 // 4MB
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val activeSendStreams = mutableListOf<SendStream>()
    private val flowsByCanvas = mutableMapOf<CanvasId, MutableSharedFlow<CanvasOp>>()

    /** Canvases this client has open: caught up again whenever a new connection comes up. */
    private val subscribedCanvases: MutableSet<CanvasId> = ConcurrentHashMap.newKeySet()
    private var acceptJob: Job? = null

    init {
        if (endpoint != null) {
            startAcceptLoop()
        }
    }

    fun startAcceptLoop(): Job {
        acceptJob?.cancel()
        val ep = endpoint ?: return Job().apply { complete() }
        val job = scope.launch {
            runCanvasAcceptLoop(ep, "CanvasSync") { handleIncomingConnection(it) }
        }
        acceptJob = job
        return job
    }

    private suspend fun handleIncomingConnection(incoming: Incoming) {
        runCatching {
            val accepting = incoming.accept()
            val peerAlpn = accepting.alpn()
            if (peerAlpn.contentEquals(CANVAS_SYNC_ALPN)) {
                val connection = accepting.connect()
                Telemetry.event("CanvasSync", "incoming.connected")
                registerConnection(connection, isInbound = true)
            }
        }.onFailure { t ->
            val errorMsg = t.message ?: t.toString()
            Telemetry.event("CanvasSync", "incoming.error", "error" to errorMsg)
        }
    }

    suspend fun connectToPeer(endpointAddr: EndpointAddr): Connection {
        val ep = endpoint ?: error("Iroh endpoint is not configured")
        Telemetry.event("CanvasSync", "dial.start")
        val connection = ep.connect(endpointAddr, CANVAS_SYNC_ALPN)
        Telemetry.event("CanvasSync", "dial.connected")
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
            }
            if (!isInbound) greet(sendStream)

            try {
                consumePackets(recvStream, sendStream)
            } finally {
                removeActiveSendStream(sendStream)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            val errorMsg = t.message ?: t.toString()
            Telemetry.event("CanvasSync", "connection.error", "error" to errorMsg)
        }
    }

    private suspend fun consumePackets(recvStream: RecvStream, sendStream: SendStream) {
        while (true) {
            val frameBytes = readFrame(recvStream) ?: break
            dispatchIncomingPacket(frameBytes, sendStream)
        }
    }

    /**
     * The first frames on a connection this side opened: catch-up requests for every open canvas,
     * or an empty hello, so the peer accepts the stream and can send on it straight away.
     */
    private suspend fun greet(sendStream: SendStream) {
        val canvases = subscribedCanvases.toList()
        val packets = if (canvases.isEmpty()) {
            listOf(CanvasOpWirePacket(canvasId = ""))
        } else {
            canvases.map { CanvasOpWirePacket(canvasId = it.value, requestCatchUpSinceLamport = 0L) }
        }
        for (packet in packets) {
            send(sendStream, packet)
        }
    }

    private suspend fun dispatchIncomingPacket(frameBytes: ByteArray, sendStream: SendStream) {
        val packet = runCatching {
            json.decodeFromString<CanvasOpWirePacket>(frameBytes.decodeToString())
        }.getOrNull() ?: return
        if (packet.canvasId.isEmpty()) return // A hello.

        val canvasId = CanvasId(packet.canvasId)
        if (packet.op != null) {
            if (relay) {
                relayOp(canvasId, packet.op, frameBytes, from = sendStream)
            } else {
                val flow = getOrCreateFlow(canvasId)
                flow.emit(packet.op)
                fallback.publish(canvasId, packet.op)
            }
        }
        val sinceLamport = packet.requestCatchUpSinceLamport
        if (sinceLamport != null && opLog != null) {
            sendCatchUpOps(canvasId, sinceLamport, sendStream)
            // The relay asks back: the client may hold edits made while it could not reach the
            // host. It answers from its own log; ops the relay has not seen are logged and passed on.
            if (relay) {
                val askBack = CanvasOpWirePacket(canvasId = canvasId.value, requestCatchUpSinceLamport = 0L)
                send(sendStream, askBack)
            }
        }
    }

    /** Logs [op] once and passes it on to every peer but the one it came from. */
    private suspend fun relayOp(canvasId: CanvasId, op: CanvasOp, payload: ByteArray, from: SendStream) {
        val log = opLog
        if (log != null) {
            if (log.has(canvasId, op.opId)) return
            log.append(canvasId, op)
        }
        broadcast(encodeFrame(payload), except = from)
    }

    private suspend fun broadcast(frame: ByteArray, except: SendStream? = null) {
        mutex.withLock {
            val iterator = activeSendStreams.iterator()
            while (iterator.hasNext()) {
                val stream = iterator.next()
                if (stream === except) continue
                try {
                    stream.write(frame)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // A peer that will not take the frame is dropped, not retried.
                    iterator.remove()
                }
            }
        }
    }

    private suspend fun sendCatchUpOps(canvasId: CanvasId, sinceLamport: Long, sendStream: SendStream) {
        val log = opLog ?: return
        val historicalOps = log.getOps(canvasId, sinceLamport)
        for (historicalOp in historicalOps) {
            send(sendStream, CanvasOpWirePacket(canvasId = canvasId.value, op = historicalOp))
        }
    }

    /** One frame to one peer, under the lock broadcasts take, so frames never interleave on a stream. */
    private suspend fun send(sendStream: SendStream, packet: CanvasOpWirePacket) {
        val frame = encodeFrame(json.encodeToString(packet).encodeToByteArray())
        mutex.withLock { runCatching { sendStream.write(frame) } }
    }

    private suspend fun removeActiveSendStream(sendStream: SendStream) {
        withContext(NonCancellable) {
            mutex.withLock {
                activeSendStreams.remove(sendStream)
            }
            runCatching { sendStream.finish() }
        }
    }

    override suspend fun publish(canvasId: CanvasId, op: CanvasOp) {
        // Also emit to local subscribers
        getOrCreateFlow(canvasId).emit(op)
        fallback.publish(canvasId, op)

        val packet = CanvasOpWirePacket(canvasId = canvasId.value, op = op)
        broadcast(encodeFrame(json.encodeToString(packet).encodeToByteArray()))
    }

    override fun subscribe(canvasId: CanvasId): Flow<CanvasOp> {
        subscribedCanvases.add(canvasId)
        scope.launch {
            requestCatchUp(canvasId, sinceLamport = 0L)
        }
        return getOrCreateFlow(canvasId).asSharedFlow()
    }

    suspend fun requestCatchUp(canvasId: CanvasId, sinceLamport: Long = 0L) {
        val packet = CanvasOpWirePacket(
            canvasId = canvasId.value,
            op = null,
            requestCatchUpSinceLamport = sinceLamport,
        )
        val payload = json.encodeToString(packet).encodeToByteArray()
        val frame = encodeFrame(payload)

        broadcast(frame)
    }

    private fun getOrCreateFlow(canvasId: CanvasId): MutableSharedFlow<CanvasOp> {
        return synchronized(flowsByCanvas) {
            flowsByCanvas.getOrPut(canvasId) {
                MutableSharedFlow(replay = 32, extraBufferCapacity = 64)
            }
        }
    }

    private fun encodeFrame(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Canvas op frame too large: ${payload.size}" }
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

