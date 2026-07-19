package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object GoalAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("goal.get") { params ->
            val agentId = params.requireParam("agent_id")
            api.get(AdminPath.v1("agents", agentId, "goal"))
        }
        router.register("goal.command") { params ->
            val agentId = params.requireParam("agent_id")
            val command = params.requireParam("command")
            val body = buildJsonObject { put("command", command) }.toString()
            api.post(AdminPath.v1("agents", agentId, "goal", "command"), body = body)
        }
    }
}
