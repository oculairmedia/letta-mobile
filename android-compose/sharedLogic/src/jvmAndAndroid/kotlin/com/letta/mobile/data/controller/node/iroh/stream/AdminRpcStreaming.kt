package com.letta.mobile.data.controller.node.iroh.stream

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds

/**
 * One streamed admin_rpc exchange as its handler sees it: the request, and a way to write
 * `admin_rpc_stream` frames before the final response.
 */
internal interface AdminRpcStreamedCall {
    /** The request id every frame and the final response carry. */
    val requestId: String

    /** The request params. */
    val params: JsonObject?

    /**
     * Writes one `admin_rpc_stream` frame now: `type`, `request_id` and the next `seq` (from 0, no gaps),
     * then [event]'s fields. A failed write throws [AdminRpcStreamWriteFailed], which cancels the call.
     */
    suspend fun emit(event: JsonObject)
}

/** The final response of a streamed exchange. */
internal sealed interface AdminRpcStreamOutcome {
    /** `success: true` with [result]. */
    data class Success(val result: JsonElement) : AdminRpcStreamOutcome

    /** `success: false` with exactly [error]. */
    data class Failure(val error: String) : AdminRpcStreamOutcome
}

/** Serves a streamed admin_rpc method on a request bi-stream of its own. */
internal interface AdminRpcStreamedHandler {
    /** The method it serves. */
    val method: String

    /** The `error` of the final response written after the client cancelled. */
    val cancelledError: String

    /**
     * Serves [call] in the serving connection's coroutine context ([AdminRpcConnection]). The coroutine
     * is cancelled when the client cancels (a cancel frame, or its send half finishing or resetting),
     * when a frame cannot be written or when the connection drops. It must release what it holds in
     * `finally`, before it returns or completes cancelled.
     */
    suspend fun serve(call: AdminRpcStreamedCall): AdminRpcStreamOutcome
}

/** A stream frame could not be written: the exchange is over, as if the client had cancelled. */
internal class AdminRpcStreamWriteFailed : CancellationException("admin_rpc stream write failed")

/**
 * Names the serving connection for handlers that keep per-connection limits. The stream server puts
 * one instance per connection into the coroutine context of every call it dispatches.
 */
internal class AdminRpcConnection : AbstractCoroutineContextElement(Key) {
    /** An id unique to this connection, for keying per-connection state. */
    val id: String = java.util.UUID.randomUUID().toString()

    /** The context key. */
    companion object Key : CoroutineContext.Key<AdminRpcConnection>
}

/** A streamed request that passed every gate: who serves it and what it asked. */
internal class AdminRpcStreamedRequest(
    val handler: AdminRpcStreamedHandler,
    val requestId: String,
    val method: String,
    val params: JsonObject?,
)

/**
 * The two halves of the request stream: [nextFrame] reads the client's next frame (null once its send
 * half finished; throws when it was reset) and [write] writes one frame to it.
 */
internal class AdminRpcStreamHalves(
    val nextFrame: suspend () -> String?,
    val write: suspend (String) -> Unit,
)

/**
 * Serves streamed admin_rpc exchanges for one connection: the handler writes `admin_rpc_stream` frames,
 * then this writes the final `admin_rpc_response`. While the handler runs, the client's send half is the
 * cancel channel: an `admin_rpc_cancel` frame for the request, or the half finishing or resetting,
 * cancels the handler, waits at most [cancelGraceMs] for it to let go, and writes the handler's
 * cancelled error. A failed frame write ends the exchange the same way. No frame follows the final
 * response. [report] receives event names and fields that never carry params or frames.
 */
internal class AdminRpcStreamedExchange(
    private val connection: AdminRpcConnection,
    private val internalError: String,
    private val report: (name: String, fields: Map<String, String>) -> Unit,
    private val cancelGraceMs: Long = CANCEL_GRACE_MS,
) {
    /** Runs [request] over [halves] to its final response. */
    suspend fun serve(request: AdminRpcStreamedRequest, halves: AdminRpcStreamHalves) = coroutineScope {
        val started = System.nanoTime()
        val frames = StreamFrames(request, halves)
        val finished = CompletableDeferred<AdminRpcStreamOutcome>()
        val work = launch(connection) { finished.complete(run(request, frames)) }
        val watcher = launch {
            val reason = awaitCancel(halves, request)
            work.cancel()
            withTimeoutOrNull(cancelGraceMs.milliseconds) { work.join() }
            if (finished.complete(AdminRpcStreamOutcome.Failure(request.handler.cancelledError))) {
                report("cancelled", fields(request, "reason" to reason))
            }
        }
        val outcome = finished.await()
        watcher.cancel()
        frames.close(outcome)
        val millis = (System.nanoTime() - started) / NANOS_PER_MILLI
        report("finished", fields(request, "outcome" to outcome.code(), "frames" to "${frames.count}", "durationMs" to "$millis"))
    }

    private suspend fun run(request: AdminRpcStreamedRequest, frames: StreamFrames): AdminRpcStreamOutcome = try {
        request.handler.serve(frames)
    } catch (expectedWriteFailure: AdminRpcStreamWriteFailed) {
        report("cancelled", fields(request, "reason" to "write_failed"))
        AdminRpcStreamOutcome.Failure(request.handler.cancelledError)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (expectedHandlerFailure: Exception) {
        report("handler_failed", fields(request, "class" to expectedHandlerFailure::class.simpleName.orEmpty()))
        AdminRpcStreamOutcome.Failure(internalError)
    }

    /** Returns why the client cancelled; suspends while its send half stays open and quiet. */
    private suspend fun awaitCancel(halves: AdminRpcStreamHalves, request: AdminRpcStreamedRequest): String {
        while (true) {
            val frame = try {
                halves.nextFrame()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expectedReset: Exception) {
                return "send_reset"
            } ?: return "send_finished"
            if (isCancelFor(frame, request)) return "cancel_frame"
        }
    }

    /** Whether [frame] is this request's `admin_rpc_cancel`; anything else on the cancel channel is ignored. */
    private fun isCancelFor(frame: String, request: AdminRpcStreamedRequest): Boolean {
        val obj = runCatching { Json.parseToJsonElement(frame) as? JsonObject }.getOrNull() ?: return false
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
        return type == CANCEL_FRAME_TYPE && (obj["request_id"] as? JsonPrimitive)?.contentOrNull == request.requestId
    }

    private fun fields(request: AdminRpcStreamedRequest, vararg extra: Pair<String, String>): Map<String, String> =
        mapOf("requestId" to request.requestId, "method" to request.method) + extra

    private fun AdminRpcStreamOutcome.code(): String = when (this) {
        is AdminRpcStreamOutcome.Success -> "success"
        is AdminRpcStreamOutcome.Failure -> error
    }

    /** The handler's view of the exchange; every write goes through one lock, so frames never interleave. */
    private inner class StreamFrames(
        private val request: AdminRpcStreamedRequest,
        private val halves: AdminRpcStreamHalves,
    ) : AdminRpcStreamedCall {
        private val lock = Mutex()
        private var seq = 0
        private var closed = false

        val count: Int get() = seq

        override val requestId: String get() = request.requestId
        override val params: JsonObject? get() = request.params

        override suspend fun emit(event: JsonObject) {
            lock.withLock {
                if (closed) throw AdminRpcStreamWriteFailed()
                val frame = buildJsonObject {
                    put("type", STREAM_FRAME_TYPE)
                    put("request_id", requestId)
                    put("seq", seq)
                    event.forEach { (key, value) -> put(key, value) }
                }
                write(frame.toString())
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
                    put("success", outcome is AdminRpcStreamOutcome.Success)
                    when (outcome) {
                        is AdminRpcStreamOutcome.Success -> put("result", outcome.result)
                        is AdminRpcStreamOutcome.Failure -> put("error", outcome.error)
                    }
                }
                runCatching { write(response.toString()) }
                    .onFailure { report("final_write_failed", fields(request)) }
            }
        }

        private suspend fun write(frame: String) {
            try {
                halves.write(frame)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expectedWriteFailure: Exception) {
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
