package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ModelApi
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.api.IModelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

open class ModelRepository(
    private val modelApi: ModelApi,
) : IModelRepository {
    private val _llmModels = MutableStateFlow<List<LlmModel>>(emptyList())
    override open val llmModels: StateFlow<List<LlmModel>> = _llmModels.asStateFlow()

    private val _embeddingModels = MutableStateFlow<List<EmbeddingModel>>(emptyList())
    override open val embeddingModels: StateFlow<List<EmbeddingModel>> = _embeddingModels.asStateFlow()

    override open suspend fun refreshLlmModels() {
        _llmModels.update { modelApi.listLlmModels() }
    }

    override open suspend fun refreshEmbeddingModels() {
        _embeddingModels.update { modelApi.listEmbeddingModels() }
    }
}
