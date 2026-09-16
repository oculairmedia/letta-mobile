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
 */
class IrohCanvasSyncTransport(
    private val scope: CoroutineScope,
    private val endpoint: Endpoint? = null,
    private val opLog: CanvasOpLog? = null,
    private val fallback: CanvasSyncTransport = LoopbackCanvasSyncTransport(),
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
    private var acceptJob: Job? = null

    init {
        if (endpoint != null) {
            startAcceptLoop()
        }
    }

    fun startAcceptLoop(): Job {
        acceptJob?.cancel()
        val ep = endpoint ?: return completedJob()
        val job = scope.launch {
            runAcceptLoop(ep)
        }
        acceptJob = job
        return job
    }

    private fun completedJob(): Job = Job().apply { complete() }

    private suspend fun CoroutineScope.runAcceptLoop(ep: Endpoint) {
        while (isActive) {
            acceptNextSafely(ep)
        }
    }

    private suspend fun CoroutineScope.acceptNextSafely(ep: Endpoint) {
        try {
            val incoming = ep.acceptNext() ?: return
            launch { handleIncomingConnection(incoming) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logAcceptError(t)
        }
    }

    private fun logAcceptError(t: Throwable) {
        val errorMsg = t.message ?: t.toString()
        Telemetry.event("CanvasSync", "accept.error", "error" to errorMsg)
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

    private suspend fun dispatchIncomingPacket(frameBytes: ByteArray, sendStream: SendStream) {
        val packet = runCatching {
            json.decodeFromString<CanvasOpWirePacket>(frameBytes.decodeToString())
        }.getOrNull() ?: return

        val canvasId = CanvasId(packet.canvasId)
        if (packet.op != null) {
            val flow = getOrCreateFlow(canvasId)
            flow.emit(packet.op)
            fallback.publish(canvasId, packet.op)
        }
        val sinceLamport = packet.requestCatchUpSinceLamport
        if (sinceLamport != null && opLog != null) {
            sendCatchUpOps(canvasId, sinceLamport, sendStream)
        }
    }

    private suspend fun sendCatchUpOps(canvasId: CanvasId, sinceLamport: Long, sendStream: SendStream) {
        val log = opLog ?: return
        val historicalOps = log.getOps(canvasId, sinceLamport)
        for (historicalOp in historicalOps) {
            val replyPacket = CanvasOpWirePacket(canvasId = canvasId.value, op = historicalOp)
            val payload = json.encodeToString(replyPacket).encodeToByteArray()
            val frame = encodeFrame(payload)
            runCatching { sendStream.write(frame) }
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

    override suspend fun publish(canvasId: CanvasId, op: CanvasOp) {
        // Also emit to local subscribers
        getOrCreateFlow(canvasId).emit(op)
        fallback.publish(canvasId, op)

        val packet = CanvasOpWirePacket(canvasId = canvasId.value, op = op)
        val payload = json.encodeToString(packet).encodeToByteArray()
        val frame = encodeFrame(payload)

        mutex.withLock {
            val iterator = activeSendStreams.iterator()
            while (iterator.hasNext()) {
                val stream = iterator.next()
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

    override fun subscribe(canvasId: CanvasId): Flow<CanvasOp> {
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

        mutex.withLock {
            for (stream in activeSendStreams) {
                runCatching { stream.write(frame) }
            }
        }
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

