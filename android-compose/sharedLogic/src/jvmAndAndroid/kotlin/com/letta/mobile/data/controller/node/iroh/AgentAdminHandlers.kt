package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.model.AgentId

/**
 * Agent CRUD handlers for the Iroh admin RPC router.
 */
object AgentAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String, controller: AppServerController? = null) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("agent.list") { params ->
            // letta-mobile-71orq: forward pagination params so the client can page
            // through ALL agents. Without a limit the server returns only its default
            // first page (~50), so agents beyond it never resolve a name in the
            // conversation list (fall back to agentId.take(8)).
            api.get(AdminPath.v1("agents")) {
                query("limit", param(params, AdminParamKey("limit")))
                query("offset", param(params, AdminParamKey("offset")))
            }
        }
        router.register("agent.get") { params ->
            val id = params.requireParam(AdminParamKey("agent_id"))
            api.get(AdminPath.v1("agents", id))
        }
        router.register("agent.create") { params ->
            api.post(AdminPath.v1("agents"), body = params?.toString() ?: "{}")
        }
        router.register("agent.update") { params ->
            val id = params.requireParam(AdminParamKey("agent_id"))
            val result = api.patch(AdminPath.v1("agents", id), body = params.toString())
            // letta-mobile-eeu5p: a model switch persists via this PATCH, but the
            // App Server caches its runtime per (agent, conversation) and keeps
            // serving the OLD model until restart. When the update changes the
            // model, evict the agent's cached runtime so the next turn reseeds
            // from the freshly-updated record.
            if (params?.get("model") != null) {
                controller?.stopRuntime(AgentId(id))
            }
            result
        }
        router.register("agent.delete") { params ->
            val id = params.requireParam(AdminParamKey("agent_id"))
            api.delete(AdminPath.v1("agents", id))
        }
        router.register("agent.context") { params ->
            val id = params.requireParam(AdminParamKey("agent_id"))
            // letta-mobile-c4igq.9: agent.context is normally KBs (counts + short
            // memory strings), but a memory-heavy agent can carry large system_prompt/
            // core_memory blocks that push a full response over the frame cap. Bound
            // oversized string fields so context always hydrates.
            MessageListPageGuard.boundObjectStringFields(
                api.get(AdminPath.v1("agents", id, "context")) {
                    query("conversation_id", param(params, AdminParamKey("conversation_id")))
                }
            )
        }
    }
}
