package com.letta.mobile.data.controller.node.iroh

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One streamed admin_rpc exchange as its handler sees it: the request, and a way to write
 * `admin_rpc_stream` frames before the final response.
 */
internal interface AdminRpcStreamedCall {
    /** The request id every frame and the final response carry. */
    val requestId: String

    /** The request params. */
    val params: JsonObject?

    /** The serving connection's authorisation context. */
    val context: AdminRpcRequestContext

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
    /** The `error` of the final response written after the client cancelled. */
    val cancelledError: String

    /**
     * Serves [call]. The coroutine is cancelled when the client cancels (a cancel frame, or its send
     * half finishing or resetting), when a frame cannot be written or when the connection drops. It must
     * release what it holds in `finally`, before it returns or completes cancelled.
     */
    suspend fun serve(call: AdminRpcStreamedCall): AdminRpcStreamOutcome
}

/** A stream frame could not be written: the exchange is over, as if the client had cancelled. */
internal class AdminRpcStreamWriteFailed : CancellationException("admin_rpc stream write failed")

/**
 * Names the serving connection for handlers that keep per-connection limits. [AdminRpcStreamServer]
 * puts one instance per connection into the coroutine context of every call it dispatches.
 */
internal class AdminRpcConnection : AbstractCoroutineContextElement(Key) {
    /** The context key. */
    companion object Key : CoroutineContext.Key<AdminRpcConnection>
}
