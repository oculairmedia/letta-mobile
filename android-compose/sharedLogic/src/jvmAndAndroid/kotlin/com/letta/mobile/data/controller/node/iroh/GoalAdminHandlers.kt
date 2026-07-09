package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

object GoalAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = GoalApi(AdminProxyClient(adminBaseUrl))
        router.register("goal.get") { api.get(it) }
        router.register("goal.command") { api.command(it) }
    }

    private class GoalApi(private val proxy: AdminProxyClient) {
        fun get(params: JsonObject?): JsonElement {
            val agentId = param(params, "agent_id") ?: return adminError("agent_id required")
            return proxy.get(adminProxyRequest("v1", "agents", agentId, "goal").build())
        }

        fun command(params: JsonObject?): JsonElement {
            val agentId = param(params, "agent_id") ?: return adminError("agent_id required")
            val command = param(params, "command") ?: return adminError("command required")
            val body = buildJsonObject { put("command", command) }.toString()
            return proxy.post(adminProxyRequest("v1", "agents", agentId, "goal", "command").build(), body)
        }
    }

    private fun param(params: JsonObject?, key: String): String? = params?.get(key)?.jsonPrimitive?.contentOrNull

}
