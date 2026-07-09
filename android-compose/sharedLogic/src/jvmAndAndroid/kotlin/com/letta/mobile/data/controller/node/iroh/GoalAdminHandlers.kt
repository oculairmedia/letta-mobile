package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

object GoalAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val proxy = AdminHandlerProxy(AdminProxyClient(adminBaseUrl))
        router.register("goal.get") { params ->
            val agentId = AdminHandlerSupport.param(params, "agent_id") ?: adminError("agent_id required")
            proxy.get("v1", "agents", agentId, "goal")
        }
        router.register("goal.command") { params ->
            val agentId = AdminHandlerSupport.param(params, "agent_id") ?: adminError("agent_id required")
            val command = AdminHandlerSupport.param(params, "command") ?: adminError("command required")
            val body = buildJsonObject { put("command", command) }.toString()
            proxy.post("v1", "agents", agentId, "goal", "command", body = body)
        }
    }
}
