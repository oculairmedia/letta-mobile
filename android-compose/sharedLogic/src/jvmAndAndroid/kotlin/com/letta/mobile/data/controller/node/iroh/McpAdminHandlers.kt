package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

object McpAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("mcp.list") { api.get("mcp", "servers") }
        router.register("passage.list") { params ->
            val agentId = param(params, "agent_id")
            if (agentId != null) api.get("agents", agentId, "passages") else adminError("agent_id required")
        }
    }
}
