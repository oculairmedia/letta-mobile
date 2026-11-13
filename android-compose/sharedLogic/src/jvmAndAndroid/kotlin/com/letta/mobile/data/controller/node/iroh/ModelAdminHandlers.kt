package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.AppServerListModelsAdapter
import com.letta.mobile.data.model.IdFormats
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerConversationRuntimeScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Model catalog, model switch and model exposure.
 *
 * - `model.list` is native (`list_models`), filtered by the wrapper exposure
 *   allow-list ([ModelExposureStore]; default = everything exposed). Hidden
 *   rows are returned only with `include_hidden=true`; every row carries
 *   `exposed` and, when upstream advertises variants, `reasoning_efforts`.
 * - `model.update` is native (`update_model`) for one agent + conversation. A successful switch
 *   pushes `conversation_updated` and/or `agent_updated` for the scope letta.js applied it to, so
 *   other devices refresh their model chips.
 * - `model.exposure.get` / `model.exposure.set` read/write the wrapper-owned
 *   exposure decisions. They never touch the App Server.
 * - `model.list.embedding` stays a controller constant.
 *
 * `provider.*` lives in [ProviderAdminHandlers].
 */
object ModelAdminHandlers {
    /** Registers the model routes; [tiers] supplies the App Server client and the change notifiers. */
    fun register(
        router: AdminRpcRouter,
        tiers: NativeReadTiers = NativeReadTiers(),
        exposure: ModelExposureStore = InMemoryModelExposureStore(),
    ) {
        val nativeClient = tiers.nativeClient
        router.register("model.list") { params -> listModels(nativeClient, exposure, params) }
        router.register("model.update") { params -> updateModel(tiers, params) }
        router.register("model.exposure.get") { exposureState(exposure.decisions()) }
        router.register("model.exposure.set") { params -> setExposure(exposure, params) }
        router.register("model.list.embedding") { NativeAdminCatalogs.embeddingModelCatalog() }
        ProviderAdminHandlers.register(router, nativeClient)
    }

    private suspend fun listModels(
        nativeClient: AppServerClient?,
        exposure: ModelExposureStore,
        params: JsonObject?,
    ): JsonArray {
        val includeHidden = param(params, AdminParamKey("include_hidden"))?.toBooleanStrictOrNull() ?: false
        return NativeAdmin.require(nativeClient, NativeAdminOp.ModelList) { c ->
            val response = c.listModels(
                AppServerCommand.ListModels(
                    requestId = NativeAdmin.requestId(),
                    force = param(params, AdminParamKey("force"))?.toBooleanStrictOrNull(),
                ),
            )
            if (!response.success) return@require null
            val raw = response.entries ?: JsonArray(emptyList())
            val adapted = AppServerListModelsAdapter.toLlmModelArray(raw)
            ModelListProjection.decorate(adapted, raw, exposure, includeHidden)
                .also { ModelControlTelemetry.modelListed(it.size, includeHidden) }
        }
    }

    private suspend fun updateModel(tiers: NativeReadTiers, params: JsonObject?): JsonObject {
        val command = ModelUpdateParams.toCommand(params, NativeAdmin.requestId())
        return NativeAdmin.require(tiers.nativeClient, NativeAdminOp.ModelUpdate) { c ->
            val response = c.updateModel(command)
            ModelControlTelemetry.modelUpdated(command, response.success, response.appliedTo)
            if (!response.success) adminError(response.error ?: "update_model failed")
            announceSwitch(tiers, command.runtime, response.appliedTo)
            buildJsonObject {
                put("agent_id", command.runtime.agentId)
                put("conversation_id", command.runtime.conversationId)
                response.appliedTo?.let { put("applied_to", it) }
                response.modelId?.let { put("model_id", it) }
                response.modelHandle?.let { put("model_handle", it) }
                response.modelSettings?.let { put("model_settings", it) }
            }
        }
    }

    /**
     * letta.js `applyModelUpdateForRuntime` reports `applied_to`: "agent" for the default
     * conversation, else "conversation". An App Server that omits it may have changed either, so
     * both are announced; the default conversation is never announced as a conversation row.
     */
    private suspend fun announceSwitch(tiers: NativeReadTiers, scope: AppServerConversationRuntimeScope, appliedTo: String?) {
        val agentId = scope.agentId?.takeIf { it.isNotBlank() }
        if (appliedTo != APPLIED_TO_AGENT && scope.conversationId != IdFormats.DEFAULT_CONVERSATION_ID) {
            tiers.conversationChanges?.notify(scope.conversationId, agentId, ConversationChangeKind.Updated)
        }
        if (appliedTo != APPLIED_TO_CONVERSATION && agentId != null) {
            tiers.agentChanges?.notify(agentId, AgentChangeKind.Updated)
        }
    }

    private const val APPLIED_TO_AGENT = "agent"
    private const val APPLIED_TO_CONVERSATION = "conversation"

    private fun setExposure(exposure: ModelExposureStore, params: JsonObject?): JsonObject {
        val changes = ModelExposureParams.changes(params)
        val next = exposure.apply(changes)
        ModelControlTelemetry.exposureChanged(changes.size, next.count { !it.value })
        return exposureState(next)
    }

    /** `{default_exposed: true, hidden: [...], models: {"<handle>": false}}`. */
    private fun exposureState(decisions: Map<String, Boolean>): JsonObject = buildJsonObject {
        put("default_exposed", true)
        put("hidden", JsonArray(decisions.filterValues { !it }.keys.sorted().map(::JsonPrimitive)))
        put("models", JsonObject(decisions.mapValues { JsonPrimitive(it.value) }))
    }

    /** Controller-owned constant catalog (no datastore, no shim). */
    val CONSTANT_CATALOG_METHODS: Set<String> = setOf("model.list.embedding")

    /** Methods the retired admin REST adapter used to own ( inventory). */
    val FORMER_ADMIN_REST_METHODS: Set<String> = CONSTANT_CATALOG_METHODS + "provider.list"

    /** Wrapper-owned exposure state; never an App Server call. */
    val EXPOSURE_METHODS: Set<String> = setOf("model.exposure.get", "model.exposure.set")

    val METHODS: Set<String> =
        setOf("model.list", "model.update") + CONSTANT_CATALOG_METHODS + EXPOSURE_METHODS + ProviderAdminHandlers.METHODS
}
