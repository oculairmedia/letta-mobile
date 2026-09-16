package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasSyncTransport
import com.letta.mobile.data.canvas.LoopbackCanvasSyncTransport
import com.letta.mobile.util.Telemetry
import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.RecvStream
import computer.iroh.SendStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    val op: CanvasOp,
)

/**
 * Dedicated Iroh QUIC transport for peer-to-peer Canvas synchronization.
 *
 * Runs independently from App Server chat WebSocket framing, operating over
 * dedicated BiStreams with length-prefixed binary framing.
 */
class IrohCanvasSyncTransport(
    private val scope: CoroutineScope,
    private val endpoint: Endpoint? = null,
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

    fun registerConnection(connection: Connection): Job = scope.launch {
        try {
            val biStream = connection.openBi()
            val sendStream = biStream.send()
            val recvStream = biStream.recv()

            mutex.withLock {
                activeSendStreams.add(sendStream)
            }

            try {
                while (true) {
                    val frameBytes = readFrame(recvStream) ?: break
                    val packet = runCatching {
                        json.decodeFromString<CanvasOpWirePacket>(frameBytes.decodeToString())
                    }.getOrNull()

                    if (packet != null) {
                        val canvasId = CanvasId(packet.canvasId)
                        val flow = getOrCreateFlow(canvasId)
                        flow.emit(packet.op)
                        fallback.publish(canvasId, packet.op)
                    }
                }
            } finally {
                mutex.withLock {
                    activeSendStreams.remove(sendStream)
                }
                runCatching { sendStream.finish() }
            }
        } catch (t: Throwable) {
            Telemetry.event("CanvasSync", "connection.error", "error" to (t.message ?: t.toString()))
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
                } catch (_: Throwable) {
                    iterator.remove()
                }
            }
        }
    }

    override fun subscribe(canvasId: CanvasId): Flow<CanvasOp> {
        return getOrCreateFlow(canvasId).asSharedFlow()
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
            if (chunk.isEmpty()) {
                if (offset == 0) return null
                return null
            }
            chunk.copyInto(prefix, destinationOffset = offset)
            offset += chunk.size
        }
        val length = ((prefix[0].toInt() and 0xff) shl 24) or
            ((prefix[1].toInt() and 0xff) shl 16) or
            ((prefix[2].toInt() and 0xff) shl 8) or
            (prefix[3].toInt() and 0xff)
        if (length !in 0..MAX_PAYLOAD_BYTES) return null
        return stream.readExact(length.toUInt())
    }
}
