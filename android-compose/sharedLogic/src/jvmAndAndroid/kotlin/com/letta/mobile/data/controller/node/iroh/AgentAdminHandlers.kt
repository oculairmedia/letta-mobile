package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent CRUD handlers for the Iroh admin RPC router.
 */
object AgentAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = AgentApi(AdminProxyClient(adminBaseUrl))
        router.register("agent.list") { api.list() }
        router.register("agent.get") { params -> api.get(params) }
        router.register("agent.create") { params -> api.create(params) }
        router.register("agent.update") { params -> api.update(params) }
        router.register("agent.delete") { params -> api.delete(params) }
    }

    private class AgentApi(private val proxy: AdminProxyClient) {
        fun list(): JsonElement = proxy.get(adminProxyRequest("v1", "agents").build())
        fun get(params: JsonObject?): JsonElement {
            val id = param(params, "agent_id") ?: return jsonError("agent_id required")
            return proxy.get(adminProxyRequest("v1", "agents", id).build())
        }
        fun create(params: JsonObject?): JsonElement = proxy.post(adminProxyRequest("v1", "agents").build(), params?.toString() ?: "{}")
        fun update(params: JsonObject?): JsonElement {
            val id = param(params, "agent_id") ?: return jsonError("agent_id required")
            return proxy.patch(adminProxyRequest("v1", "agents", id).build(), params.toString())
        }
        fun delete(params: JsonObject?): JsonElement {
            val id = param(params, "agent_id") ?: return jsonError("agent_id required")
            return proxy.delete(adminProxyRequest("v1", "agents", id).build())
        }
    }

    private fun param(params: JsonObject?, key: String): String? = params?.get(key)?.jsonPrimitive?.contentOrNull
    private fun jsonError(message: String): JsonElement = buildJsonObject { put("_error", message) }
}
