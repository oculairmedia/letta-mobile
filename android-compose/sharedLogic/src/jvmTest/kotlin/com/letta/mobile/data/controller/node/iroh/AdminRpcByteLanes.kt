package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.link.NodeCallExchange
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** The receive half of a test call lane carrying length-prefixed Iroh frames. */
internal interface AdminRpcRecvStream {
    suspend fun read(maxBytes: UInt): ByteArray
}

/** The send half of a test call lane carrying length-prefixed Iroh frames. */
internal interface AdminRpcSendStream {
    suspend fun writeAll(bytes: ByteArray)
    suspend fun finish()
}

/** A test call lane as raw bytes, the way an Iroh bi-stream carries one. */
internal interface AdminRpcBiStream {
    fun recv(): AdminRpcRecvStream
    fun send(): AdminRpcSendStream
}

/**
 * [stream] as a call exchange, framed the way the Iroh carrier frames a call lane: length-prefixed frames, a request
 * bounded to one frame until the peer has authenticated and advertised `frame_part`, and a response split into
 * `frame_part`s only for such a peer. Production call lanes come from a carrier; these byte lanes let the router
 * tests keep driving the server with raw frames.
 */
internal fun AdminRpcStreamServer.byteLane(stream: AdminRpcBiStream): NodeCallExchange {
    val sendStream = stream.send()
    val decoder = IrohFrameCodec.Decoder(maxFrameBytes, maxReassembledBytesProvider = {
        if (authenticated.get() && peerSupportsFrameParts()) maxReassembledBytes else maxFrameBytes
    })
    val recv = stream.recv()
    val pending = ArrayDeque<String>()
    return object : NodeCallExchange {
        override suspend fun next(): String? {
            while (pending.isEmpty()) {
                val chunk = recv.read(READ_CHUNK_SIZE.toUInt())
                if (chunk.isEmpty()) {
                    decoder.finish()
                    return null
                }
                pending.addAll(decoder.feed(chunk))
            }
            return pending.removeFirst()
        }

        override suspend fun write(frame: String) = sendStream.writeAll(IrohFrameCodec.encodeFrame(frame, maxFrameBytes))

        override suspend fun respond(response: String) {
            val payload = response.encodeToByteArray()
            val buffers = if (payload.size > maxFrameBytes && peerSupportsFrameParts()) {
                IrohFrameCodec.encodeFrameParts(payload, maxFrameBytes, maxReassembledBytes)
            } else {
                listOf(IrohFrameCodec.encodeFrame(payload, maxFrameBytes))
            }
            buffers.forEach { sendStream.writeAll(it) }
        }

        override suspend fun finish() = sendStream.finish()
    }
}

/** Serves exactly one exchange on the byte lane [stream]. */
internal suspend fun AdminRpcStreamServer.handleStream(stream: AdminRpcBiStream) = handleExchange(byteLane(stream))

/** Serves byte lanes from [acceptBi] until it returns null, as [AdminRpcStreamServer.serveCalls] serves call lanes. */
internal suspend fun AdminRpcStreamServer.serveAcceptLoop(acceptBi: suspend () -> AdminRpcBiStream?) =
    serveCalls { acceptBi()?.let { byteLane(it) } }

/** Serves the byte lane [stream] on its own job in [scope], under the server's handler permits. */
internal fun AdminRpcStreamServer.launchHandler(scope: CoroutineScope, stream: AdminRpcBiStream): Job =
    launchCall(scope, byteLane(stream))

private const val READ_CHUNK_SIZE = 8192
