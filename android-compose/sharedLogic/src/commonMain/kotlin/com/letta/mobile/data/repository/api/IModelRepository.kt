package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import kotlinx.coroutines.flow.StateFlow

interface IModelRepository {
    val llmModels: StateFlow<List<LlmModel>>
    val embeddingModels: StateFlow<List<EmbeddingModel>>
    suspend fun refreshLlmModels()
    suspend fun refreshEmbeddingModels()
}
