package com.letta.mobile.data.transport.appserver

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

import kotlin.time.Duration.Companion.milliseconds
/**
 * Generic RPC client for admin operations over the Iroh control channel.
 *
 * Sends [AppServerCommand.AdminRpc] frames and correlates responses by
 * [requestId]. Each call produces a fresh request ID and waits for a matching
 * [AppServerInboundFrame.AdminRpcResponse] on the control frame flow.
 *
 * Usage:
 * ```
 * val client = IrohAdminRpcClient(transport.controlFrames) { cmd ->
 *     transport.sendControl(cmd)
 * }
 * val result = client.call("agent.list", buildJsonObject { ... })
 * ```
 *
 * Thread-safe: each call creates its own collector + deferred pair.
 */
class IrohAdminRpcClient(
    private val controlFrames: Flow<AppServerReceivedFrame>,
    private val send: suspend (AppServerCommand.AdminRpc) -> Unit,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val timeoutMs: Long = DEFAULT_RPC_TIMEOUT_MS,
) {
    /**
     * Makes an admin RPC call. Suspends until a matching response arrives
     * or the timeout elapses.
     *
     * @param T The expected result type (must be a JsonElement subtype)
     * @param method The RPC method name (e.g. "agent.list")
     * @param params Optional JSON params for the method
     * @param parser Lambda to convert the response result JSON to T
     * @return The parsed result
     * @throws IrohAdminRpcException on non-success responses or timeout
     */
    suspend fun <T> call(
        method: String,
        params: JsonObject? = null,
        parser: (JsonElement) -> T,
    ): T = coroutineScope {
        val requestId = nextRequestId()
        val deferred = CompletableDeferred<JsonElement>()

        val collector = launch {
            controlFrames.collect { received ->
                val frame = received.frame
                if (frame is AppServerInboundFrame.AdminRpcResponse && frame.requestId == requestId) {
                    if (!deferred.isCompleted) {
                        if (frame.success && frame.result != null) {
                            deferred.complete(frame.result)
                        } else {
                            deferred.completeExceptionally(
                                IrohAdminRpcException(method, frame.error ?: "Unknown RPC error")
                            )
                        }
                    }
                }
            }
        }

        try {
            send(AppServerCommand.AdminRpc(requestId = requestId, method = method, params = params))
            withTimeout(timeoutMs.milliseconds) {
                val result = deferred.await()
                parser(result)
            }
        } finally {
            collector.cancelAndJoin()
        }
    }

    companion object {
        const val DEFAULT_RPC_TIMEOUT_MS: Long = 30_000L

        private val nextId = kotlinx.atomicfu.atomic(0)

        private fun nextRequestId(): String {
            return "rpc-${nextId.getAndIncrement()}"
        }
    }
}

class IrohAdminRpcException(
    val method: String,
    override val message: String,
) : Exception("RPC $method failed: $message")
