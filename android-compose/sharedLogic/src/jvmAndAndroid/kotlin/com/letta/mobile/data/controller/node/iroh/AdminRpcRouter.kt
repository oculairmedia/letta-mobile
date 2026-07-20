package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.transport.iroh.AdminRpcErrors
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Throws an IllegalArgumentException so the router's catch path returns success:false.
 */
fun adminError(message: String): Nothing = throw IllegalArgumentException(message)

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
data class AdminRpcRequestContext(
    val authenticated: Boolean,
    val authorizedConversationIds: Set<String>? = null,
) {
    fun canAccessConversation(conversationId: String): Boolean =
        authenticated && (authorizedConversationIds == null || conversationId in authorizedConversationIds)

    companion object {
        val Authenticated = AdminRpcRequestContext(authenticated = true)
        val Unauthenticated = AdminRpcRequestContext(authenticated = false, authorizedConversationIds = emptySet())
    }
}

/** Bundles dispatch inputs so the router API stays free of multi-string arity. */
data class AdminRpcInvocation(
    val requestId: String,
    val method: String,
    val params: JsonObject?,
    val context: AdminRpcRequestContext = AdminRpcRequestContext.Authenticated,
)

class AdminRpcRouter(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val handlers = mutableMapOf<String, suspend (JsonObject?, AdminRpcRequestContext) -> JsonElement>()

    val methodCount: Int
        get() = handlers.size

    val registeredMethods: Set<String>
        get() = handlers.keys.toSet()

    /**
     * Registers a handler for the given method name.
     * Method names should use dot-notation: "agent.list", "conversation.get", etc.
     */
    fun register(method: String, handler: suspend (JsonObject?) -> JsonElement) {
        registerScoped(method) { params, _ -> handler(params) }
    }

    fun registerScoped(
        method: String,
        handler: suspend (JsonObject?, AdminRpcRequestContext) -> JsonElement,
    ) {
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
    suspend fun dispatch(invocation: AdminRpcInvocation): String {
        val handler = handlers[invocation.method]
        if (handler == null) {
            Telemetry.event("AdminRpc", "method.not_found", "method" to invocation.method)
            return encodeFailure(invocation.requestId, AdminRpcErrors.unknownMethod(invocation.method))
        }
        return try {
            val result = handler(invocation.params, invocation.context)
            json.encodeToString(
                kotlinx.serialization.serializer(),
                buildJsonObject {
                    put("type", "admin_rpc_response")
                    put("request_id", invocation.requestId)
                    put("success", true)
                    put("result", result)
                },
            )
        } catch (e: Exception) {
            Telemetry.event(
                "AdminRpc",
                "handler.error",
                "method" to invocation.method,
                "error" to (e.message ?: ""),
            )
            encodeFailure(invocation.requestId, e.message ?: "Internal error")
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
