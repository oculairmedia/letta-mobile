package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerTransport
import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.letta.mobile.util.Telemetry

/**
 * Iroh-backed App Server transport.
 *
 * Implements the [AppServerTransport] interface over QUIC connections via the
 * iroh-ffi binding. Models the App Server's dual-channel protocol (control + stream)
 * as two QUIC bidirectional streams over a single iroh connection.
 *
 * The control channel is used for sending commands and receiving responses with
 * request_id correlation. The stream channel is receive-only and carries streaming
 * events (stream_delta, update_loop_status, etc.).
 *
 * App Server v2 frames are sent/received UNCHANGED — this is pure transport,
 * not protocol transformation.
 *
 * @param endpoint The iroh endpoint to use for connections (must be bound)
 * @param remoteAddr The remote endpoint address (NodeID + optional relay/direct addrs)
 * @param alpn The ALPN protocol identifier (e.g., "/letta/appserver/0")
 * @param scope The coroutine scope for transport I/O jobs
 * @param protocol The App Server protocol codec (typically [AppServerProtocol])
 */
class IrohAppServerTransport(
    private val endpoint: Endpoint,
    private val remoteAddr: EndpointAddr,
    private val alpn: ByteArray,
    scope: CoroutineScope,
    private val protocol: AppServerProtocol = AppServerProtocol,
) : AppServerTransport {
    private val controlCommandQueue = Channel<AppServerCommand>(Channel.BUFFERED)
    private val controlFrameFlow = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = FRAME_BUFFER_CAPACITY)
    private val streamFrameFlow = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = FRAME_BUFFER_CAPACITY)

    // Connection and streams, initialized in background jobs
    private lateinit var connection: Connection
    private lateinit var controlBiStream: BiStream
    private lateinit var streamBiStream: BiStream
    private val connectionReady = CompletableDeferred<Unit>()

    // Catches any Iroh FFI exception that escapes per-coroutine error handling
    // (e.g. IrohError timed out from keepalive, stream reader, or accept loops).
    // Without this, an uncaught IrohException in a scope.launch propagates to
    // the thread's default uncaught exception handler and crashes the app.
    private val irohExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Telemetry.event("IrohTransport", "crash.caught", "error" to (throwable.message ?: throwable.toString()), "class" to throwable::class.simpleName)
    }

    private val controlJob = scope.launch(irohExceptionHandler) {
        runControlChannel()
    }

    private val streamJob = scope.launch(irohExceptionHandler) {
        connectionReady.await()
        runStreamChannel()
    }

    // c0qm0 instrumentation: log WHY/WHEN the QUIC connection closes. If the
    // connection drops mid-conversation, this names the cause (idle, server
    // close, error) so the multi-turn churn can be diagnosed precisely.
    private val closeWatcher = scope.launch {
        runCatching { connectionReady.await() }
        val reason = runCatching { connection.closed() }.getOrNull()
        Telemetry.event("IrohTransport", "connection.closed", "reason" to (reason ?: "<unknown>"))
    }

    // c0qm0 keepalive: Iroh exposes no idle-timeout knob, so proactively keep the
    // QUIC connection warm by sending a tiny datagram on an interval. A datagram
    // on the wire resets the peer's idle timer and prevents the mid-conversation
    // drop that orphaned later turns' response streams. The server ignores these
    // (it never reads datagrams); they exist only to keep the path alive.
    private val keepAliveJob = scope.launch {
        runCatching { connectionReady.await() }
        while (true) {
            kotlinx.coroutines.delay(KEEPALIVE_INTERVAL_MS)
            val ok = runCatching { connection.sendDatagram(KEEPALIVE_PAYLOAD) }.isSuccess
            if (!ok) {
                Telemetry.event("IrohTransport", "keepalive.stopped")
                break
            }
        }
    }

    override val controlFrames: Flow<AppServerReceivedFrame> = controlFrameFlow.asSharedFlow()
    override val streamFrames: Flow<AppServerReceivedFrame> = streamFrameFlow.asSharedFlow()

    override suspend fun sendControl(command: AppServerCommand) {
        Telemetry.event("IrohTransport", "control.enqueue", "command" to command::class.simpleName)
        controlCommandQueue.send(command)
    }

    suspend fun close() {
        controlCommandQueue.close()
        runCatching { keepAliveJob.cancelAndJoin() }
        runCatching { closeWatcher.cancelAndJoin() }
        controlJob.cancelAndJoin()
        streamJob.cancelAndJoin()
        
        // Clean up streams and connection
        runCatching { controlBiStream.send().finish() }
        runCatching { streamBiStream.send().finish() }
        runCatching { connection.close() }
    }

    private suspend fun runControlChannel() = coroutineScope {
        // Establish connection and open control bi-stream
        try {
            connection = withTimeout(CONNECT_TIMEOUT_MS) {
                endpoint.connect(remoteAddr, alpn)
            }
            Telemetry.event("IrohTransport", "connect.ok")
            controlBiStream = connection.openBi()
            Telemetry.event("IrohTransport", "control.opened")
            connectionReady.complete(Unit)
        } catch (t: Throwable) {
            Telemetry.event("IrohTransport", "connect.failed", "error" to (t.message ?: t.toString()), "class" to t::class.simpleName)
            connectionReady.completeExceptionally(t)
            return@coroutineScope
        }

        val sender = controlCommandQueue
        val receiver = controlBiStream.recv()
        val sendStream = controlBiStream.send()

        // Launch sender coroutine
        val senderJob = launch {
            for (command in sender) {
                val json = protocol.encodeCommand(command)
                val bytes = json.encodeToByteArray()
                Telemetry.event("IrohTransport", "control.write", "command" to command::class.simpleName, "bytes" to bytes.size)
                sendStream.writeAll(bytes)
                // Write newline delimiter so receiver can split frames
                sendStream.writeAll("\n".toByteArray())
            }
        }

        try {
            // Read frames from control channel
            receiveFrames(receiver, AppServerChannel.Control, controlFrameFlow)
        } finally {
            senderJob.cancelAndJoin()
        }
    }

    private suspend fun runStreamChannel() {
        // Open stream bi-stream (we only read from this, but QUIC requires bi-directional)
        streamBiStream = connection.openBi()
        Telemetry.event("IrohTransport", "stream.opened")
        
        // The stream channel is receive-only in the App Server protocol,
        // but we signal the remote that we're ready by sending a newline
        streamBiStream.send().writeAll("\n".toByteArray())
        streamBiStream.send().finish()
        
        receiveFrames(streamBiStream.recv(), AppServerChannel.Stream, streamFrameFlow)
    }

    private suspend fun receiveFrames(
        recvStream: computer.iroh.RecvStream,
        channel: AppServerChannel,
        sink: MutableSharedFlow<AppServerReceivedFrame>,
    ) {
        val buffer = mutableListOf<Byte>()
        
        while (true) {
            // Safety limit: if a frame line exceeds MAX_LINE_BYTES the stream
            // is junk or the line delimiter broke. Flush and continue rather than
            // growing unbounded (causes OOM, observed 86MB allocation).
            if (buffer.size > MAX_LINE_BYTES) {
                Telemetry.event("IrohTransport", "frame.line_overflow", "channel" to channel.name, "bytes" to buffer.size)
                buffer.clear()
            }
            // Read chunks from the stream
            val chunk = runCatching {
                recvStream.read(READ_CHUNK_SIZE.toUInt())
            }.getOrNull() ?: break // Stream closed or error
            
            if (chunk.isEmpty()) break // EOF
            
            // Accumulate bytes and split on newline
            for (byte in chunk) {
                if (byte == '\n'.code.toByte()) {
                    // Process accumulated line as a frame
                    if (buffer.isNotEmpty()) {
                        val json = buffer.toByteArray().decodeToString()
                        buffer.clear()
                        
                        val frame = decodeFrame(json, channel)
                        Telemetry.event("IrohTransport", "frame.recv", "channel" to channel.name, "type" to frame.frame.type)
                        sink.emit(frame)
                    }
                } else {
                    buffer.add(byte)
                }
            }
        }
        
        // Process any remaining data
        if (buffer.isNotEmpty()) {
            val json = buffer.toByteArray().decodeToString()
            val frame = decodeFrame(json, channel)
            Telemetry.event("IrohTransport", "frame.recv", "channel" to channel.name, "type" to frame.frame.type)
            sink.emit(frame)
        }
    }

    private fun decodeFrame(rawText: String, channel: AppServerChannel): AppServerReceivedFrame =
        runCatching {
            protocol.decodeFrame(rawText, channel)
        }.getOrElse { error ->
            val raw = buildJsonObject {
                put("type", "decode_error")
                put("raw", rawText)
                put("message", error.message ?: "Failed to decode App Server frame")
            }
            AppServerReceivedFrame(
                channel = channel,
                frame = AppServerInboundFrame.Unknown(type = "decode_error", raw = raw),
                raw = raw,
            )
        }

    private companion object {
        const val FRAME_BUFFER_CAPACITY = 64
        const val READ_CHUNK_SIZE = 8192
        // Max bytes per line before the frame reader clears its buffer as an OOM
        // safety guard (observed 86MB allocation from unbounded accumulation).
        const val MAX_LINE_BYTES = 1_048_576 // 1MB
        // c0qm0: keep the QUIC connection warm across turns. 15s is well under
        // typical QUIC idle timeouts (~30s) so the path never goes cold between
        // user messages.
        const val KEEPALIVE_INTERVAL_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 120_000L
        val KEEPALIVE_PAYLOAD = byteArrayOf(0)
    }
}
