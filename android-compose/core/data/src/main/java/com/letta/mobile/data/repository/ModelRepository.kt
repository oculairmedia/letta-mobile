package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ModelApi
import com.letta.mobile.data.model.AgentRuntimeBinding
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.api.IModelRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.LocalRuntimeModelSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

open class ModelRepository(
    private val modelApi: ModelApi,
    private val localModelSource: LocalRuntimeModelSource? = null,
    private val settingsRepository: ISettingsRepository? = null,
    // letta-mobile P4 iroh purity: when the active backend is iroh://, model
    // catalog reads MUST route over admin_rpc — the raw HTTP ModelApi
    // hard-fails at the LettaApiClient choke-point, which left the model
    // picker dropdown empty.
    private val irohModelSource: IrohAdminRpcModelSource? = null,
) : IModelRepository {
    private val _llmModels = MutableStateFlow<List<LlmModel>>(emptyList())
    override open val llmModels: StateFlow<List<LlmModel>> = _llmModels.asStateFlow()

    private val _embeddingModels = MutableStateFlow<List<EmbeddingModel>>(emptyList())
    override open val embeddingModels: StateFlow<List<EmbeddingModel>> = _embeddingModels.asStateFlow()

    private fun isLocalRuntimeActive(): Boolean =
        localModelSource != null && AgentRuntimeBinding.isLocalRuntime(settingsRepository?.activeConfig?.value)

    override open suspend fun refreshLlmModels() {
        // Local-runtime mode: pickers list downloaded embedded models; the
        // remote model API is unreachable (and guarded) behind a local config.
        val localSource = localModelSource
        if (localSource != null && isLocalRuntimeActive()) {
            _llmModels.update { localSource.listLlmModels() }
            return
        }
        val irohSource = irohModelSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            _llmModels.update { irohSource.listLlmModels() }
            return
        }
        _llmModels.update { modelApi.listLlmModels() }
    }

    override open suspend fun refreshEmbeddingModels() {
        if (isLocalRuntimeActive()) {
            // The embedded runtime has no embedding models.
            _embeddingModels.update { emptyList() }
            return
        }
        val irohSource = irohModelSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            _embeddingModels.update { irohSource.listEmbeddingModels() }
            return
        }
        _embeddingModels.update { modelApi.listEmbeddingModels() }
    }
}
