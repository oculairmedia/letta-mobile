package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.runtime.DEFAULT_APP_SERVER_CONTEXT_WINDOW_LIMIT
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Agent CRUD handlers for the Iroh admin RPC router.
 *
 * Phase 2: app_server_v2-owned operations are fail-closed on the native client.
 * There is no LettaShim or direct-disk fallback for these methods.
 */
object AgentAdminHandlers {
    fun register(
        router: AdminRpcRouter,
        adminBaseUrl: String,
        controller: AppServerController? = null,
        tiers: NativeReadTiers = NativeReadTiers(),
    ) {
        val nativeClient = tiers.nativeClient
        // adminBaseUrl retained only for agent.context (admin_rest / Phase 3).
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("agent.list") { params ->
            val limit = param(params, AdminParamKey("limit"))
            val offset = param(params, AdminParamKey("offset"))
            NativeAdmin.require(nativeClient, "agent.list") { c ->
                val response = c.agentList(
                    AppServerCommand.AgentList(
                        requestId = NativeAdmin.requestId(),
                        query = NativeAdmin.queryOf(
                            "limit" to limit,
                            "offset" to offset,
                        ),
                    ),
                )
                if (response.success) response.agents ?: JsonArray(emptyList()) else null
            }
        }
        router.register("agent.get") { params ->
            val id = params.requireParam(AdminParamKey("agent_id"))
            NativeAdmin.require(nativeClient, "agent.get") { c ->
                val response = c.agentRetrieve(
                    AppServerCommand.AgentRetrieve(requestId = NativeAdmin.requestId(), agentId = id),
                )
                if (response.success) response.agent else null
            }
        }
        router.register("agent.create") { params ->
            val body = params.withDefaultContextWindow()
            NativeAdmin.require(nativeClient, "agent.create") { c ->
                val response = c.agentCreate(
                    AppServerCommand.AgentCreate(
                        requestId = NativeAdmin.requestId(),
                        body = body,
                    ),
                )
                if (response.success) response.agent else null
            }
        }
        router.register("agent.update") { params ->
            val id = params.requireParam(AdminParamKey("agent_id"))
            val body = if (params?.get("model") != null) params.withDefaultContextWindow() else params
            val result = NativeAdmin.require(nativeClient, "agent.update") { c ->
                val response = c.agentUpdate(
                    AppServerCommand.AgentUpdate(
                        requestId = NativeAdmin.requestId(),
                        agentId = id,
                        body = body ?: buildJsonObject { },
                    ),
                )
                if (response.success) response.agent else null
            }
            // Evict cached runtime when model or context-window inputs change so
            // the next turn reseeds from the updated agent record.
            if (shouldInvalidateRuntime(params)) {
                controller?.stopRuntime(AgentId(id))
            }
            result
        }
        router.register("agent.delete") { params ->
            val id = params.requireParam(AdminParamKey("agent_id"))
            NativeAdmin.require(nativeClient, "agent.delete") { c ->
                val response = c.agentDelete(
                    AppServerCommand.AgentDelete(requestId = NativeAdmin.requestId(), agentId = id),
                )
                if (response.success) buildJsonObject { put("deleted", true) } as JsonObject else null
            }
        }
        router.register("agent.context") { params ->
            val id = params.requireParam(AdminParamKey("agent_id"))
            // letta-mobile-c4igq.9: agent.context is normally KBs (counts + short
            // memory strings), but a memory-heavy agent can carry large system_prompt/
            // core_memory blocks that push a full response over the frame cap. Bound
            // oversized string fields so context always hydrates.
            //
            // Additionally, /context inlines the ENTIRE in-context `messages` array
            // which the client's ContextWindowOverview never reads. Drop it before
            // bounding the remaining strings.
            MessageListPageGuard.boundObjectStringFields(
                MessageListPageGuard.dropField(
                    api.get(AdminPath.v1("agents", id, "context")) {
                        query("conversation_id", param(params, AdminParamKey("conversation_id")))
                    },
                    "messages",
                ),
            )
        }
    }

    private fun shouldInvalidateRuntime(params: JsonObject?): Boolean {
        if (params == null) return false
        if (params["model"] != null) return true
        if (params["context_window_limit"] != null || params["contextWindowLimit"] != null) return true
        val modelSettings = params["model_settings"]?.jsonObject
        return modelSettings?.containsKey("context_window_limit") == true ||
            modelSettings?.containsKey("contextWindowLimit") == true
    }
}

private fun JsonObject?.withDefaultContextWindow(): JsonObject =
    buildJsonObject {
        this@withDefaultContextWindow?.forEach { (key, value) -> put(key, value) }
        val modelSettings = this@withDefaultContextWindow?.get("model_settings") as? JsonObject
        val hasExplicitLimit =
            this@withDefaultContextWindow?.get("context_window_limit") != null ||
                this@withDefaultContextWindow?.get("contextWindowLimit") != null ||
                modelSettings?.get("context_window_limit") != null ||
                modelSettings?.get("contextWindowLimit") != null
        if (!hasExplicitLimit) {
            put("context_window_limit", DEFAULT_APP_SERVER_CONTEXT_WINDOW_LIMIT)
        }
    }
