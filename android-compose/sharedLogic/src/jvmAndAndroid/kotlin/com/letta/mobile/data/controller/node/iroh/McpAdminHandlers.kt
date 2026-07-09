package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

object McpAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val proxy = AdminHandlerProxy(AdminProxyClient(adminBaseUrl))
        router.register("mcp.list") { proxy.get("v1", "mcp", "servers") }
        router.register("passage.list") { params ->
            val agentId = AdminHandlerSupport.param(params, "agent_id")
            if (agentId != null) proxy.get("v1", "agents", agentId, "passages") else adminError("agent_id required")
        }
    }
}
