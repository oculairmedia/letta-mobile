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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

    private val controlJob = scope.launch {
        runControlChannel()
    }

    private val streamJob = scope.launch {
        runStreamChannel()
    }

    override val controlFrames: Flow<AppServerReceivedFrame> = controlFrameFlow.asSharedFlow()
    override val streamFrames: Flow<AppServerReceivedFrame> = streamFrameFlow.asSharedFlow()

    override suspend fun sendControl(command: AppServerCommand) {
        controlCommandQueue.send(command)
    }

    suspend fun close() {
        controlCommandQueue.close()
        controlJob.cancelAndJoin()
        streamJob.cancelAndJoin()
        
        // Clean up streams and connection
        runCatching { controlBiStream.send().finish() }
        runCatching { streamBiStream.send().finish() }
        runCatching { connection.close() }
    }

    private suspend fun runControlChannel() {
        // Establish connection and open control bi-stream
        connection = endpoint.connect(remoteAddr, alpn)
        controlBiStream = connection.openBi()

        val sender = controlCommandQueue
        val receiver = controlBiStream.recv()
        val sendStream = controlBiStream.send()

        // Launch sender coroutine
        val senderJob = kotlinx.coroutines.coroutineScope {
            launch {
                for (command in sender) {
                    val json = protocol.encodeCommand(command)
                    val bytes = json.encodeToByteArray()
                    sendStream.write(bytes)
                    // Write newline delimiter so receiver can split frames
                    sendStream.write("\n".toByteArray())
                }
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
        
        // The stream channel is receive-only in the App Server protocol,
        // but we signal the remote that we're ready by sending a newline
        streamBiStream.send().write("\n".toByteArray())
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
    }
}
