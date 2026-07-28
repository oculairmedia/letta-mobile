package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ModelCatalogNormalizer
import com.letta.mobile.data.runtime.DEFAULT_APP_SERVER_CONTEXT_WINDOW_LIMIT
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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

private fun JsonObject?.withDefaultContextWindow(): JsonObject =
    buildJsonObject {
        this@withDefaultContextWindow?.forEach { (key, value) -> put(key, value) }
        val modelHandle = firstModelHandle(this@withDefaultContextWindow)
        val known = ModelCatalogNormalizer.knownLimitsForHandle(modelHandle)
        val defaultLimit = known?.contextWindow ?: DEFAULT_APP_SERVER_CONTEXT_WINDOW_LIMIT
        val modelSettings = this@withDefaultContextWindow?.get("model_settings") as? JsonObject
            ?: this@withDefaultContextWindow?.get("modelSettings") as? JsonObject
        val llmConfig = this@withDefaultContextWindow?.get("llm_config") as? JsonObject
            ?: this@withDefaultContextWindow?.get("llmConfig") as? JsonObject
        val hasExplicitLimit =
            this@withDefaultContextWindow?.get("context_window_limit") != null ||
                this@withDefaultContextWindow?.get("contextWindowLimit") != null ||
                modelSettings?.get("context_window_limit") != null ||
                modelSettings?.get("contextWindowLimit") != null ||
                llmConfig?.get("context_window") != null ||
                llmConfig?.get("context_window_limit") != null ||
                llmConfig?.get("contextWindow") != null ||
                llmConfig?.get("contextWindowLimit") != null
        if (!hasExplicitLimit) {
            put("context_window_limit", defaultLimit)
        }
        // Persist max_output when known and absent so tool/context turns stay bounded.
        val hasExplicitOutput =
            modelSettings?.get("max_output_tokens") != null ||
                modelSettings?.get("maxOutputTokens") != null ||
                this@withDefaultContextWindow?.get("max_output_tokens") != null
        if (!hasExplicitOutput && known != null) {
            val existingSettings = modelSettings ?: buildJsonObject { }
            put(
                "model_settings",
                buildJsonObject {
                    existingSettings.forEach { (k, v) -> put(k, v) }
                    put("max_output_tokens", known.maxOutputTokens)
                },
            )
        }
    }

private fun firstModelHandle(body: JsonObject?): String? {
    if (body == null) return null
    val direct = (body["model"] as? JsonPrimitive)?.contentOrNull
        ?: (body["handle"] as? JsonPrimitive)?.contentOrNull
    if (!direct.isNullOrBlank()) return direct
    val settings = body["model_settings"] as? JsonObject
        ?: body["modelSettings"] as? JsonObject
    return (settings?.get("handle") as? JsonPrimitive)?.contentOrNull
        ?: (settings?.get("model") as? JsonPrimitive)?.contentOrNull
}
