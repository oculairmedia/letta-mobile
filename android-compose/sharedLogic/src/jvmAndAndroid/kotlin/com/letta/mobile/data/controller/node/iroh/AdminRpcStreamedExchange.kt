package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryValue
import com.letta.mobile.util.attr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds

/** Reads length-prefixed frames off one request stream, keeping any frames that arrived together. */
internal class AdminRpcFrameReader(
    private val recv: AdminRpcRecvStream,
    private val decoder: IrohFrameCodec.Decoder,
) {
    private val pending = ArrayDeque<String>()

    /** The next frame, or null when the peer finished its send half. Throws when the stream was reset. */
    suspend fun next(): String? {
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

    private companion object {
        const val READ_CHUNK_SIZE = 8192
    }
}

/** The pieces of one streamed exchange: who serves it, the request and both halves of its stream. */
internal class AdminRpcStreamedRequest(
    val handler: AdminRpcStreamedHandler,
    val invocation: AdminRpcInvocation,
    val reader: AdminRpcFrameReader,
    val send: AdminRpcSendStream,
)

/**
 * Serves one streamed admin_rpc exchange: the handler writes `admin_rpc_stream` frames, then this writes
 * the final `admin_rpc_response`. While the handler runs, the client's send half is the cancel channel:
 * an `admin_rpc_cancel` frame for the request, or the half finishing or resetting, cancels the handler,
 * waits at most [cancelGraceMs] for it to let go, and writes the handler's cancelled error. A failed
 * frame write ends the exchange the same way. No frame is written after the final response.
 */
internal class AdminRpcStreamedExchange(
    private val connection: AdminRpcConnection,
    private val remoteEndpointId: String,
    private val maxFrameBytes: Int,
    private val json: Json,
    private val cancelGraceMs: Long = CANCEL_GRACE_MS,
) {
    /** Runs [request] to its final response. */
    suspend fun serve(request: AdminRpcStreamedRequest) = coroutineScope {
        val started = System.nanoTime()
        val frames = StreamFrames(request)
        val finished = CompletableDeferred<AdminRpcStreamOutcome>()
        val work = launch(connection) { finished.complete(run(request, frames)) }
        val watcher = launch {
            val reason = awaitCancel(request.reader, request.invocation.requestId)
            work.cancel()
            withTimeoutOrNull(cancelGraceMs.milliseconds) { work.join() }
            if (finished.complete(AdminRpcStreamOutcome.Failure(request.handler.cancelledError))) {
                report(request, "cancelled", "reason" attr reason)
            }
        }
        val outcome = finished.await()
        watcher.cancel()
        frames.close(outcome)
        report(
            request, "finished",
            "outcome" attr outcome.code(), "frames" attr frames.count,
            "durationMs" attr (System.nanoTime() - started) / NANOS_PER_MILLI,
        )
    }

    private suspend fun run(request: AdminRpcStreamedRequest, frames: StreamFrames): AdminRpcStreamOutcome = try {
        request.handler.serve(frames)
    } catch (writeFailed: AdminRpcStreamWriteFailed) {
        report(request, "cancelled", "reason" attr "write_failed")
        AdminRpcStreamOutcome.Failure(request.handler.cancelledError)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        report(request, "handler_failed", "class" attr error::class.simpleName)
        AdminRpcStreamOutcome.Failure(ADMIN_RPC_INTERNAL_ERROR)
    }

    /** Returns why the client cancelled; suspends while its send half stays open and quiet. */
    private suspend fun awaitCancel(reader: AdminRpcFrameReader, requestId: String): String {
        while (true) {
            val frame = try {
                reader.next()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (reset: Exception) {
                return "send_reset"
            } ?: return "send_finished"
            val obj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: continue
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            if (type == CANCEL_FRAME_TYPE && obj["request_id"]?.jsonPrimitive?.contentOrNull == requestId) return "cancel_frame"
        }
    }

    private fun report(request: AdminRpcStreamedRequest, name: String, vararg extra: Pair<String, TelemetryValue>) {
        Telemetry.event(
            "IrohNode", "admin_rpc.stream.$name",
            "remoteEndpointId" attr remoteEndpointId,
            "requestId" attr request.invocation.requestId,
            "method" attr request.invocation.method,
            *extra,
        )
    }

    private fun AdminRpcStreamOutcome.code(): String = when (this) {
        is AdminRpcStreamOutcome.Success -> "success"
        is AdminRpcStreamOutcome.Failure -> error
    }

    /** The handler's view of the exchange; every write goes through one lock, so frames never interleave. */
    private inner class StreamFrames(private val request: AdminRpcStreamedRequest) : AdminRpcStreamedCall {
        private val lock = Mutex()
        private var seq = 0
        private var closed = false

        val count: Int get() = seq

        override val requestId: String get() = request.invocation.requestId
        override val params: JsonObject? get() = request.invocation.params
        override val context: AdminRpcRequestContext get() = request.invocation.context

        override suspend fun emit(event: JsonObject) {
            lock.withLock {
                if (closed) throw AdminRpcStreamWriteFailed()
                val frame = buildJsonObject {
                    put("type", STREAM_FRAME_TYPE)
                    put("request_id", requestId)
                    put("seq", seq)
                    event.forEach { (key, value) -> put(key, value) }
                }
                write(json.encodeToString(JsonObject.serializer(), frame))
                seq++
            }
        }

        /** Writes the final response once; a failure to write it is only reported. */
        suspend fun close(outcome: AdminRpcStreamOutcome) {
            lock.withLock {
                closed = true
                val response = buildJsonObject {
                    put("type", "admin_rpc_response")
                    put("request_id", requestId)
                    when (outcome) {
                        is AdminRpcStreamOutcome.Success -> {
                            put("success", true)
                            put("result", outcome.result)
                        }
                        is AdminRpcStreamOutcome.Failure -> {
                            put("success", false)
                            put("error", outcome.error)
                        }
                    }
                }
                runCatching { write(json.encodeToString(JsonObject.serializer(), response)) }
                    .onFailure { report(request, "final_write_failed", "class" attr it::class.simpleName) }
            }
        }

        private suspend fun write(frame: String) {
            val bytes = IrohFrameCodec.encodeFrame(frame, maxFrameBytes)
            try {
                request.send.writeAll(bytes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                throw AdminRpcStreamWriteFailed()
            }
        }
    }

    companion object {
        /** How long a cancelled handler gets to let go before the cancelled response is written. */
        const val CANCEL_GRACE_MS = 200L
        const val STREAM_FRAME_TYPE = "admin_rpc_stream"
        const val CANCEL_FRAME_TYPE = "admin_rpc_cancel"
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
