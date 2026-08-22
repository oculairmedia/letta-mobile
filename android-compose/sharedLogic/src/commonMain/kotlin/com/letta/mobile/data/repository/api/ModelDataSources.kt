package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel

interface ModelRemoteSource {
    suspend fun listLlmModels(): List<LlmModel>
    suspend fun listEmbeddingModels(): List<EmbeddingModel>
}

interface ModelIrohSource {
    fun shouldUseIroh(): Boolean
    suspend fun listLlmModels(): List<LlmModel>
    suspend fun listEmbeddingModels(): List<EmbeddingModel>
}
