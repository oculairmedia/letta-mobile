package com.letta.mobile.data.repository

import com.letta.mobile.data.model.AgentRuntimeBinding
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.ModelCatalogNormalizer
import com.letta.mobile.data.repository.api.IModelRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.LocalRuntimeModelSource
import com.letta.mobile.data.repository.api.ModelIrohSource
import com.letta.mobile.data.repository.api.ModelRemoteSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Phase 5l: platform-neutral cached model catalog repository. */
open class CachedModelRepository(
    private val remote: ModelRemoteSource,
    private val localModelSource: LocalRuntimeModelSource? = null,
    private val settingsRepository: ISettingsRepository? = null,
    private val irohModelSource: ModelIrohSource? = null,
) : IModelRepository {
    private val _llmModels = MutableStateFlow<List<LlmModel>>(emptyList())
    override val llmModels: StateFlow<List<LlmModel>> = _llmModels.asStateFlow()

    private val _embeddingModels = MutableStateFlow<List<EmbeddingModel>>(emptyList())
    override val embeddingModels: StateFlow<List<EmbeddingModel>> = _embeddingModels.asStateFlow()

    private fun isLocalRuntimeActive(): Boolean =
        localModelSource != null && AgentRuntimeBinding.isLocalRuntime(settingsRepository?.activeConfig?.value)

    override suspend fun refreshLlmModels() {
        val localSource = localModelSource
        if (localSource != null && isLocalRuntimeActive()) {
            _llmModels.update { localSource.listLlmModels() }
            return
        }
        val irohSource = irohModelSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            _llmModels.update { ModelCatalogNormalizer.normalize(irohSource.listLlmModels()) }
            return
        }
        _llmModels.update { ModelCatalogNormalizer.normalize(remote.listLlmModels()) }
    }

    override suspend fun refreshEmbeddingModels() {
        if (isLocalRuntimeActive()) {
            _embeddingModels.update { emptyList() }
            return
        }
        val irohSource = irohModelSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            _embeddingModels.update { irohSource.listEmbeddingModels() }
            return
        }
        _embeddingModels.update { remote.listEmbeddingModels() }
    }
}
