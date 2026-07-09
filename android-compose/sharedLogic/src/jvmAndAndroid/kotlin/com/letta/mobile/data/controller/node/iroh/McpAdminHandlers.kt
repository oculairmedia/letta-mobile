package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

object McpAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = Api(AdminProxyClient(adminBaseUrl))
        router.register("mcp.list") { api.get("mcp", "servers") }
        router.register("passage.list") { params ->
            val agentId = param(params, "agent_id")
            if (agentId != null) api.get("agents", agentId, "passages") else adminError("agent_id required")
        }
    }

    private class Api(private val proxy: AdminProxyClient) {
        fun get(vararg segments: String): JsonElement = proxy.get(adminProxyRequest("v1", *segments).build())
    }

    private fun param(params: JsonObject?, key: String): String? = params?.get(key)?.jsonPrimitive?.contentOrNull

}
