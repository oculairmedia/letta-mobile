package com.letta.mobile.data.repository.modelcontrol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * App Server providers (letta-mobile-w4q4p): list what can be connected,
 * connect with an auth method's field values, disconnect. The wrapper answers
 * every mutation with the refreshed catalog, which replaces [providers].
 */
class ProviderConnectionRepository(private val rpc: AdminRpcInvoker) {
    private val _providers = MutableStateFlow<List<ConnectableProvider>>(emptyList())
    val providers: StateFlow<List<ConnectableProvider>> = _providers.asStateFlow()

    suspend fun refresh(): List<ConnectableProvider> =
        ModelControlWire.providers(rpc.invoke(ModelControlWire.PROVIDER_LIST, EMPTY)).also { _providers.value = it }

    suspend fun connect(request: ProviderConnectRequest): ProviderMutationResult =
        apply(rpc.invoke(ModelControlWire.PROVIDER_CONNECT, ModelControlWire.connectParams(request)))

    suspend fun disconnect(target: ProviderDisconnectTarget): ProviderMutationResult {
        val params = buildJsonObject {
            put("provider_id", target.providerId.value)
            target.providerName?.let { put("provider_name", it) }
        }
        return apply(rpc.invoke(ModelControlWire.PROVIDER_DISCONNECT, params))
    }

    private fun apply(result: kotlinx.serialization.json.JsonElement?): ProviderMutationResult =
        ModelControlWire.mutation(result).also { _providers.value = it.providers }
}

/**
 * The host's model catalog INCLUDING hidden models, with the wrapper's
 * exposure decision per handle. Pickers read [exposedModels]; the model
 * browser reads [models] and toggles with [setExposed].
 */
class ModelCatalogRepository(private val rpc: AdminRpcInvoker) {
    private val _models = MutableStateFlow<List<CatalogModel>>(emptyList())
    val models: StateFlow<List<CatalogModel>> = _models.asStateFlow()

    val exposedModels: List<CatalogModel> get() = _models.value.filter { it.exposed }

    suspend fun refresh(force: Boolean = false): List<CatalogModel> {
        val params = buildJsonObject {
            put("include_hidden", true)
            if (force) put("force", true)
        }
        return ModelControlWire.catalog(rpc.invoke(ModelControlWire.MODEL_LIST, params)).also { _models.value = it }
    }

    /** Optimistic: flips the row first and restores it if the wrapper rejects the change. */
    suspend fun setExposed(change: ExposureChange) {
        val before = _models.value
        _models.update { rows -> rows.map { if (it.handle == change.handle) it.copy(exposed = change.exposed) else it } }
        try {
            rpc.invoke(ModelControlWire.MODEL_EXPOSURE_SET, exposureParams(change))
        } catch (e: Exception) {
            _models.value = before
            throw e
        }
    }

    /** Reasoning variants upstream advertises for [handle]; empty when none. */
    fun reasoningEffortsFor(handle: ModelHandle?): List<String> =
        handle?.let { h -> _models.value.firstOrNull { it.handle == h }?.reasoningEfforts }.orEmpty()

    private fun exposureParams(change: ExposureChange): JsonObject = buildJsonObject {
        put("handle", change.handle.value)
        put("exposed", JsonPrimitive(change.exposed))
    }
}

/**
 * Switches one conversation's model (and reasoning effort) through `model.update`.
 * Every successful model switch is recorded in [selections] so pickers show the
 * conversation's model rather than the agent's (letta-mobile-okvyf).
 */
class ConversationModelRepository(
    private val rpc: AdminRpcInvoker,
    val selections: ConversationModelSelections = ConversationModelSelections(),
) {
    suspend fun updateModel(
        target: ConversationModelTarget,
        modelHandle: ModelHandle?,
        effort: ReasoningEffortChoice = ReasoningEffortChoice.Unchanged,
    ): ConversationModelUpdate {
        require(modelHandle != null || effort != ReasoningEffortChoice.Unchanged) { "nothing to update" }
        val params = ModelControlWire.updateParams(target, modelHandle, effort)
        val update = ModelControlWire.modelUpdate(rpc.invoke(ModelControlWire.MODEL_UPDATE, params))
        if (modelHandle != null) {
            if (update.appliedTo == APPLIED_TO_AGENT) {
                // The agent's own model moved; the refreshed agent is the source of truth.
                selections.clear(target.conversationId)
            } else {
                selections.record(target.conversationId, update.modelHandle ?: modelHandle.value)
            }
        }
        return update
    }

    private companion object {
        const val APPLIED_TO_AGENT = "agent"
    }
}

private val EMPTY = JsonObject(emptyMap())
