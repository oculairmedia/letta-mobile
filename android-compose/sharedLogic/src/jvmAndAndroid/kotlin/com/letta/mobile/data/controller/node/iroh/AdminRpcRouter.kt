package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Routes incoming admin RPC calls to registered handler functions.
 *
 * Each domain bead registers its handlers here. The [IrohNodeConnection] calls
 * [dispatch] when it receives an "admin_rpc" control frame.
 *
 * Handler signature: `suspend (JsonObject?) -> JsonElement`
 * - Input: the RPC params (may be null)
 * - Output: the JSON result to send back in admin_rpc_response
 *
 * @param controller The App Server controller for runtime management
 * @param json JSON instance for parsing
 */
class AdminRpcRouter(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val handlers = mutableMapOf<String, suspend (JsonObject?) -> JsonElement>()

    val methodCount: Int
        get() = handlers.size

    val registeredMethods: Set<String>
        get() = handlers.keys.toSet()

    /**
     * Registers a handler for the given method name.
     * Method names should use dot-notation: "agent.list", "conversation.get", etc.
     */
    fun register(method: String, handler: suspend (JsonObject?) -> JsonElement) {
        handlers[method] = handler
        Telemetry.event("AdminRpc", "handler.registered", "method" to method)
    }

    fun requireNonEmpty(minMethods: Int = 1): AdminRpcRouter {
        require(handlers.size >= minMethods) {
            "Admin RPC router has ${handlers.size} registered methods; expected at least $minMethods"
        }
        return this
    }

    fun copyHandlersFrom(other: AdminRpcRouter): AdminRpcRouter {
        handlers.clear()
        handlers.putAll(other.handlers)
        return requireNonEmpty(other.methodCount)
    }

    /**
     * Dispatches an RPC call to the registered handler.
     * Returns the JSON response frame or an error frame if the method is unknown
     * or the handler throws.
     */
    suspend fun dispatch(requestId: String, method: String, params: JsonObject?): String {
        val handler = handlers[method]
        if (handler == null) {
            Telemetry.event("AdminRpc", "method.not_found", "method" to method)
            return encodeFailure(requestId, "Unknown method: $method")
        }
        return try {
            val result = handler(params)
            json.encodeToString(
                kotlinx.serialization.serializer(),
                buildJsonObject {
                    put("type", "admin_rpc_response")
                    put("request_id", requestId)
                    put("success", true)
                    put("result", result)
                },
            )
        } catch (e: Exception) {
            Telemetry.event("AdminRpc", "handler.error", "method" to method, "error" to (e.message ?: ""))
            encodeFailure(requestId, e.message ?: "Internal error")
        }
    }

    private fun encodeFailure(requestId: String, message: String): String =
        json.encodeToString(
            kotlinx.serialization.serializer(),
            buildJsonObject {
                put("type", "admin_rpc_response")
                put("request_id", requestId)
                put("success", false)
                put("error", message)
            },
        )
}
