package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.util.Telemetry
import computer.iroh.BiStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal interface AdminRpcRecvStream {
    suspend fun read(maxBytes: UInt): ByteArray
}

internal interface AdminRpcSendStream {
    suspend fun writeAll(bytes: ByteArray)
    suspend fun finish()
}

internal interface AdminRpcBiStream {
    fun recv(): AdminRpcRecvStream
    fun send(): AdminRpcSendStream
}

internal fun BiStream.asAdminRpcBiStream(): AdminRpcBiStream = object : AdminRpcBiStream {
    override fun recv(): AdminRpcRecvStream = object : AdminRpcRecvStream {
        private val delegate = this@asAdminRpcBiStream.recv()
        override suspend fun read(maxBytes: UInt): ByteArray = delegate.read(maxBytes)
    }

    override fun send(): AdminRpcSendStream = object : AdminRpcSendStream {
        private val delegate = this@asAdminRpcBiStream.send()
        override suspend fun writeAll(bytes: ByteArray) = delegate.writeAll(bytes)
        override suspend fun finish() = delegate.finish()
    }
}

/**
 * Server side of stream-per-request admin_rpc.
 *
 * Extracted from [IrohNodeConnection] so the accept/dispatch lifecycle is
 * testable without a live QUIC connection. Semantics mirror the reviewed
 * inline implementation from the stream-per-request slice (letta-mobile-3wwid):
 * exactly one `admin_rpc` request frame per bi-stream, answered with exactly
 * one `admin_rpc_response`, then the stream is finished. Authentication
 * happens on the control channel; this server only observes the shared
 * [authenticated] flag.
 */
internal class AdminRpcStreamServer(
    private val router: AdminRpcRouter,
    private val authenticated: AtomicBoolean,
    private val remoteEndpointId: String = "",
    private val maxActiveHandlers: Int = DEFAULT_MAX_ACTIVE_HANDLERS,
    private val firstFrameTimeoutMs: Long = DEFAULT_FIRST_FRAME_TIMEOUT_MS,
    private val maxFrameBytes: Int = IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES,
    /**
     * Whether the connected peer advertised the `frame_part` capability in its
     * auth handshake. Responses larger than [maxFrameBytes] are chunked into
     * frame_part continuations only when this returns true; otherwise they are
     * replaced by a typed error envelope so old peers fail cleanly instead of
     * receiving framing they cannot decode.
     */
    private val peerSupportsFrameParts: () -> Boolean = { false },
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val permits = Semaphore(maxActiveHandlers)
    private val activeHandlers = AtomicInteger(0)
    private val maxObservedActiveHandlers = AtomicInteger(0)

    val activeHandlerCount: Int
        get() = activeHandlers.get()

    val maxObservedHandlerCount: Int
        get() = maxObservedActiveHandlers.get()

    /**
     * Accepts admin_rpc bi-streams until [acceptBi] returns null or the scope
     * is cancelled. Each stream is handled on its own child job under a
     * supervisor scope so one failing stream never tears down siblings or the
     * accept loop itself.
     */
    suspend fun serveAcceptLoop(acceptBi: suspend () -> AdminRpcBiStream?) = supervisorScope {
        while (true) {
            val stream = acceptBi() ?: break
            Telemetry.event("IrohNode", "admin_rpc.stream.accepted", "remoteEndpointId" to remoteEndpointId)
            launchHandler(stream)
        }
    }

    fun CoroutineScope.launchHandler(stream: AdminRpcBiStream): Job = launch {
        permits.withPermit {
            val active = activeHandlers.incrementAndGet()
            maxObservedActiveHandlers.updateMax(active)
            try {
                handleStream(stream)
            } finally {
                activeHandlers.decrementAndGet()
            }
        }
    }

    /** Serves exactly one admin_rpc request/response exchange on [stream]. */
    suspend fun handleStream(stream: AdminRpcBiStream) {
        val sendStream = stream.send()
        try {
            val frameJson = withTimeoutOrNull(firstFrameTimeoutMs) {
                readOneFrame(stream.recv())
            }
            val response = if (frameJson == null) {
                errorEnvelope("", "admin_rpc request stream closed before request")
            } else {
                handleFrame(frameJson)
            }
            runCatching { writeResponse(sendStream, response) }
                .onFailure { writeError ->
                    Telemetry.event(
                        "IrohNode", "admin_rpc.stream.error_response_failed",
                        "remoteEndpointId" to remoteEndpointId,
                        "error" to (writeError.message ?: writeError.toString()),
                        "class" to writeError::class.simpleName,
                    )
                }
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Exception) {
            Telemetry.event(
                "IrohNode", "admin_rpc.stream.failed",
                "remoteEndpointId" to remoteEndpointId,
                "error" to (error.message ?: error.toString()),
                "class" to error::class.simpleName,
            )
            runCatching {
                sendStream.writeAll(
                    IrohFrameCodec.encodeFrame(
                        errorEnvelope("", error.message ?: "admin_rpc stream failed"),
                        maxFrameBytes,
                    ),
                )
            }
        } finally {
            runCatching { sendStream.finish() }
        }
    }

    /**
     * Writes [response] as a single frame when it fits, as frame_part
     * continuations when the peer negotiated the capability, or — for
     * oversized responses to capability-off peers — as a small typed error
     * envelope (never partial/corrupt framing the peer cannot decode).
     */
    private suspend fun writeResponse(sendStream: AdminRpcSendStream, response: String) {
        val payload = response.encodeToByteArray()
        val buffers = when {
            payload.size <= maxFrameBytes -> listOf(IrohFrameCodec.encodeFrame(payload, maxFrameBytes))
            peerSupportsFrameParts() -> IrohFrameCodec.encodeFrameParts(payload, maxFrameBytes)
            else -> {
                val requestId = runCatching {
                    json.parseToJsonElement(response).jsonObject["request_id"]?.jsonPrimitive?.contentOrNull
                }.getOrNull().orEmpty()
                Telemetry.event(
                    "IrohNode", "admin_rpc.stream.response_too_large",
                    "remoteEndpointId" to remoteEndpointId,
                    "requestId" to requestId,
                    "bytes" to payload.size,
                    "maxFrameBytes" to maxFrameBytes,
                    level = Telemetry.Level.WARN,
                )
                listOf(
                    IrohFrameCodec.encodeFrame(
                        errorEnvelope(
                            requestId,
                            "admin_rpc response too large: ${payload.size} bytes > max $maxFrameBytes " +
                                "and peer did not advertise the ${IrohFrameCodec.FRAME_PART_CAPABILITY} capability",
                        ),
                        maxFrameBytes,
                    ),
                )
            }
        }
        buffers.forEach { sendStream.writeAll(it) }
    }

    private suspend fun readOneFrame(recv: AdminRpcRecvStream): String? {
        val decoder = IrohFrameCodec.Decoder(maxFrameBytes)
        while (true) {
            val chunk = recv.read(READ_CHUNK_SIZE.toUInt())
            if (chunk.isEmpty()) {
                decoder.finish()
                return null
            }
            val frames = decoder.feed(chunk)
            if (frames.isNotEmpty()) return frames.first()
        }
    }

    private suspend fun handleFrame(frameJson: String): String {
        return try {
            val obj = json.parseToJsonElement(frameJson).jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            val requestId = obj["request_id"]?.jsonPrimitive?.contentOrNull
            val method = obj["method"]?.jsonPrimitive?.contentOrNull
            Telemetry.event(
                "IrohNode", "admin_rpc.stream.recv",
                "remoteEndpointId" to remoteEndpointId,
                "type" to type, "requestId" to requestId, "method" to method,
            )
            if (type != "admin_rpc") {
                errorEnvelope(requestId ?: "", "Expected admin_rpc frame")
            } else if (!authenticated.get()) {
                val id = requestId ?: ""
                Telemetry.event(
                    "IrohNode", "auth.required",
                    "remoteEndpointId" to remoteEndpointId,
                    "requestId" to id, "reason" to "missing_token",
                )
                errorEnvelope(id, "unauthorized")
            } else if (method == null || requestId == null) {
                errorEnvelope(requestId ?: "", "method and request_id are required")
            } else {
                router.dispatch(requestId, method, obj["params"]?.jsonObject)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Exception) {
            errorEnvelope("", "Failed to parse admin_rpc frame: ${error.message ?: error.toString()}")
        }
    }

    private fun errorEnvelope(requestId: String, message: String): String =
        json.encodeToString(
            kotlinx.serialization.serializer(),
            buildJsonObject {
                put("type", "admin_rpc_response")
                put("request_id", requestId)
                put("success", false)
                put("error", message)
            },
        )

    private fun AtomicInteger.updateMax(value: Int) {
        while (true) {
            val current = get()
            if (value <= current || compareAndSet(current, value)) return
        }
    }

    companion object {
        const val DEFAULT_MAX_ACTIVE_HANDLERS = 16
        const val DEFAULT_FIRST_FRAME_TIMEOUT_MS = 15_000L
        private const val READ_CHUNK_SIZE = 8192
    }
}
