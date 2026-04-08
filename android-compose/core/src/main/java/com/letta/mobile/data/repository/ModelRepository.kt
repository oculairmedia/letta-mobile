package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ModelApi
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepository @Inject constructor(
    private val modelApi: ModelApi,
) {
    private val _llmModels = MutableStateFlow<List<LlmModel>>(emptyList())
    val llmModels: StateFlow<List<LlmModel>> = _llmModels.asStateFlow()

    private val _embeddingModels = MutableStateFlow<List<EmbeddingModel>>(emptyList())
    val embeddingModels: StateFlow<List<EmbeddingModel>> = _embeddingModels.asStateFlow()

    suspend fun refreshLlmModels() {
        _llmModels.update { modelApi.listLlmModels() }
    }

    suspend fun refreshEmbeddingModels() {
        _embeddingModels.update { modelApi.listEmbeddingModels() }
    }
}
