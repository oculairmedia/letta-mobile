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
        val proxy = AdminHandlerProxy(AdminProxyClient(adminBaseUrl))
        // letta-mobile-71orq: forward pagination params so the client can page
        // through ALL agents. Without a limit the server returns only its default
        // first page (~50), so agents beyond it never resolve a name in the
        // conversation list (fall back to agentId.take(8)).
        router.register("agent.list") { params ->
            proxy.get("v1", "agents") {
                query("limit", AdminHandlerSupport.param(params, "limit"))
                query("offset", AdminHandlerSupport.param(params, "offset"))
            }
        }
        router.register("agent.get") { params ->
            val id = AdminHandlerSupport.param(params, "agent_id") ?: adminError("agent_id required")
            proxy.get("v1", "agents", id)
        }
        router.register("agent.create") { params ->
            proxy.post("v1", "agents", body = params?.toString() ?: "{}")
        }
        router.register("agent.update") { params ->
            val id = AdminHandlerSupport.param(params, "agent_id") ?: adminError("agent_id required")
            proxy.patch("v1", "agents", id, body = params.toString())
        }
        router.register("agent.delete") { params ->
            val id = AdminHandlerSupport.param(params, "agent_id") ?: adminError("agent_id required")
            proxy.delete("v1", "agents", id)
        }
        router.register("agent.context") { params ->
            val id = AdminHandlerSupport.param(params, "agent_id") ?: adminError("agent_id required")
            proxy.get("v1", "agents", id, "context") {
                query("conversation_id", AdminHandlerSupport.param(params, "conversation_id"))
            }
        }
    }
}
