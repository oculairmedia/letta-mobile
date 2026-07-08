package com.letta.mobile.data.repository

import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Model-catalog reads over the Iroh admin RPC control channel.
 *
 * Same P4-purity class as [IrohAdminRpcAgentSource] (letta-mobile-71orq): the
 * LettaApiClient choke-point hard-fails raw HTTP admin calls in `iroh://`
 * mode, so [ModelRepository.refreshLlmModels]'s raw `ModelApi` call threw and
 * the model picker dropdown rendered empty. The server side already registers
 * `model.list` (ModelAdminHandlers proxying `/v1/models`); this is the missing
 * client wiring.
 */
class IrohAdminRpcModelSource(
    private val channelTransport: IChannelTransport,
    private val settingsRepository: ISettingsRepository,
    // Match the raw API client Json config (explicit nulls coerced to defaults).
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    },
) {
    fun shouldUseIroh(): Boolean =
        IrohChannelTransport.shouldUseIroh(settingsRepository.activeConfig.value?.serverUrl)

    suspend fun listLlmModels(): List<LlmModel> {
        val response = channelTransport.adminRpc(
            method = "model.list",
            path = "/v1/models",
            body = "{}",
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc model.list failed")
        val result = response.result ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(LlmModel.serializer()), result)
    }

    suspend fun listEmbeddingModels(): List<EmbeddingModel> {
        val response = channelTransport.adminRpc(
            method = "model.list.embedding",
            path = "/v1/models/embedding",
            body = "{}",
        )
        // Embedding models are optional over Iroh; an unregistered method or
        // failure degrades to an empty picker rather than an error screen.
        if (!response.success) return emptyList()
        val result = response.result ?: return emptyList()
        return runCatching {
            json.decodeFromJsonElement(ListSerializer(EmbeddingModel.serializer()), result)
        }.getOrDefault(emptyList())
    }
}
