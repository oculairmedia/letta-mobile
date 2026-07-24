package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Agent CRUD handlers for the Iroh admin RPC router. lgns8.7: operations
 * classified app_server_v2 try the native v2 command first and fall back to
 * the shim proxy until cutover.
 */
object AgentAdminHandlers {
    fun register(
        router: AdminRpcRouter,
        adminBaseUrl: String,
        controller: AppServerController? = null,
        nativeClient: AppServerClient? = null,
        // lgns8.9: when configured, read agent.list from the on-disk backend store
        // directly (retiring the shim proxy for that read). Null = disabled = the
        // pre-lgns8.9 proxy behavior, so production is unaffected until enabled.
        localStore: LocalBackendAdminStore? = null,
    ) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("agent.list") { params ->
            // letta-mobile-71orq: forward pagination params so the client can page
            // through ALL agents. Without a limit the server returns only its default
            // first page (~50), so agents beyond it never resolve a name in the
            // conversation list (fall back to agentId.take(8)).
            val limit = param(params, AdminParamKey("limit"))?.toIntOrNull()
            val offset = param(params, AdminParamKey("offset"))?.toIntOrNull()
            NativeAdmin.attempt(nativeClient, "agent.list") { c ->
                val response = c.agentList(
                    AppServerCommand.AgentList(
                        requestId = NativeAdmin.requestId(),
                        query = NativeAdmin.queryOf(
                            "limit" to param(params, AdminParamKey("limit")),
                            "offset" to param(params, AdminParamKey("offset")),
                        ),
                    ),
                )
                if (response.success) response.agents ?: JsonArray(emptyList()) else null
            }
                // lgns8.9 native-store tier: try the on-disk store (returns null on
                // any error), else fall through to the shim proxy exactly as before.
                ?: localStore?.listAgentsProjected(limit, offset)
                ?: api.get(AdminPath.v1("agents")) {
                    query("limit", param(params, AdminParamKey("limit")))
                    query("offset", param(params, AdminParamKey("offset")))
                }
        }
        router.register("agent.get") { params ->
            val id = params.requireParam(AdminParamKey("agent_id"))
            NativeAdmin.attempt(nativeClient, "agent.get") { c ->
                val response = c.agentRetrieve(
                    AppServerCommand.AgentRetrieve(requestId = NativeAdmin.requestId(), agentId = id),
                )
                if (response.success) response.agent else null
            } ?: api.get(AdminPath.v1("agents", id))
        }
        router.register("agent.create") { params ->
            NativeAdmin.attempt(nativeClient, "agent.create") { c ->
                val response = c.agentCreate(
                    AppServerCommand.AgentCreate(
                        requestId = NativeAdmin.requestId(),
                        body = params ?: buildJsonObject { },
                    ),
                )
                if (response.success) response.agent else null
            } ?: api.post(AdminPath.v1("agents"), body = params?.toString() ?: "{}")
        }
        router.register("agent.update") { params ->
            val id = params.requireParam(AdminParamKey("agent_id"))
            val result = NativeAdmin.attempt(nativeClient, "agent.update") { c ->
                val response = c.agentUpdate(
                    AppServerCommand.AgentUpdate(
                        requestId = NativeAdmin.requestId(),
                        agentId = id,
                        body = params ?: buildJsonObject { },
                    ),
                )
                if (response.success) response.agent else null
            } ?: api.patch(AdminPath.v1("agents", id), body = params.toString())
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
            NativeAdmin.attempt(nativeClient, "agent.delete") { c ->
                val response = c.agentDelete(
                    AppServerCommand.AgentDelete(requestId = NativeAdmin.requestId(), agentId = id),
                )
                if (response.success) buildJsonObject { put("deleted", true) } as JsonObject else null
            } ?: api.delete(AdminPath.v1("agents", id))
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
