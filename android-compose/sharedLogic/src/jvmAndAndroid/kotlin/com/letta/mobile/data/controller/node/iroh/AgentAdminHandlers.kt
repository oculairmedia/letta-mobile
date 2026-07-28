package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        controller: AppServerController? = null,
        tiers: NativeReadTiers = NativeReadTiers(),
        adminRestBaseUrl: String? = null,
    ) {
        val nativeClient = tiers.nativeClient
        router.register("agent.list") { params ->
            val limit = param(params, AdminParamKey("limit"))
            val offset = param(params, AdminParamKey("offset"))
            NativeAdmin.require(nativeClient, NativeAdminOp.AgentList) { c ->
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
            NativeAdmin.require(nativeClient, NativeAdminOp.AgentGet) { c ->
                val response = c.agentRetrieve(
                    AppServerCommand.AgentRetrieve(requestId = NativeAdmin.requestId(), agentId = id),
                )
                if (response.success) response.agent else null
            }
        }
        router.register("agent.create") { params ->
            val body = params.withDefaultContextWindow()
            NativeAdmin.require(nativeClient, NativeAdminOp.AgentCreate) { c ->
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
            // Never inject a default context_window_limit on update — model-only
            // patches (Desktop / AdminChatModelCoordinator) must keep the agent's
            // existing limit. Defaults apply on create only.
            val body = params
            val result = NativeAdmin.require(nativeClient, NativeAdminOp.AgentUpdate) { c ->
                val response = c.agentUpdate(
                    AppServerCommand.AgentUpdate(
                        requestId = NativeAdmin.requestId(),
                        agentId = id,
                        body = body ?: buildJsonObject { },
                    ),
                )
                if (response.success) response.agent else null
            }
            if (RuntimeInvalidationPolicy.agentUpdateRequiresRestart(params)) {
                controller?.stopRuntime(AgentId(id))
            }
            result
        }
        router.register("agent.delete") { params ->
            val id = params.requireParam(AdminParamKey("agent_id"))
            NativeAdmin.require(nativeClient, NativeAdminOp.AgentDelete) { c ->
                val response = c.agentDelete(
                    AppServerCommand.AgentDelete(requestId = NativeAdmin.requestId(), agentId = id),
                )
                if (response.success) buildJsonObject { put("deleted", true) } as JsonObject else null
            }
        }
        if (adminRestBaseUrl == null) {
            CapabilityUnavailable.register(router, setOf("agent.context"), service = "admin_rest")
        } else {
            val api = AdminHandlerSupport(AdminProxyClient(adminRestBaseUrl))
            router.register("agent.context") { params ->
                val id = params.requireParam(AdminParamKey("agent_id"))
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
    }

}

