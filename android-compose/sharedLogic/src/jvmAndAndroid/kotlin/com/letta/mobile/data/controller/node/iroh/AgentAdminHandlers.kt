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
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("agent.list") { params ->
            // letta-mobile-71orq: forward pagination params so the client can page
            // through ALL agents. Without a limit the server returns only its default
            // first page (~50), so agents beyond it never resolve a name in the
            // conversation list (fall back to agentId.take(8)).
            api.get(
                adminProxyRequest("v1", "agents")
                    .query("limit", param(params, "limit"))
                    .query("offset", param(params, "offset"))
                    .build()
            )
        }
        router.register("agent.get") { params ->
            val id = param(params, "agent_id") ?: return@register adminError("agent_id required")
            api.get("agents", id)
        }
        router.register("agent.create") { params ->
            api.post("agents", body = params?.toString() ?: "{}")
        }
        router.register("agent.update") { params ->
            val id = param(params, "agent_id") ?: return@register adminError("agent_id required")
            api.patch("agents", id, body = params.toString())
        }
        router.register("agent.delete") { params ->
            val id = param(params, "agent_id") ?: return@register adminError("agent_id required")
            api.delete("agents", id)
        }
        router.register("agent.context") { params ->
            val id = param(params, "agent_id") ?: return@register adminError("agent_id required")
            api.get(
                adminProxyRequest("v1", "agents", id, "context")
                    .query("conversation_id", param(params, "conversation_id"))
                    .build()
            )
        }
    }
}
