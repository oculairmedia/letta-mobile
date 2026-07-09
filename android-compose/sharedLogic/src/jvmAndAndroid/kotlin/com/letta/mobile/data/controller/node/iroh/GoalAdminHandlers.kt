package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

object GoalAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("goal.get") { params ->
            val agentId = param(params, "agent_id") ?: return@register adminError("agent_id required")
            api.get("agents", agentId, "goal")
        }
        router.register("goal.command") { params ->
            val agentId = param(params, "agent_id") ?: return@register adminError("agent_id required")
            val command = param(params, "command") ?: return@register adminError("command required")
            val body = buildJsonObject { put("command", command) }.toString()
            api.post("agents", agentId, "goal", "command", body = body)
        }
    }
}
