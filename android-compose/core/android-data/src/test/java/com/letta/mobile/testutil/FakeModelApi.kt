package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.ModelApi
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import io.mockk.mockk

class FakeModelApi : ModelApi(mockk(relaxed = true)) {
    var llmModels = mutableListOf<LlmModel>()
    var embeddingModels = mutableListOf<EmbeddingModel>()
    var shouldFail = false
    val calls = mutableListOf<String>()

    override suspend fun listLlmModels(): List<LlmModel> {
        calls.add("listLlmModels")
        if (shouldFail) throw ApiException(500, "Server error")
        return llmModels.toList()
    }

    override suspend fun listEmbeddingModels(): List<EmbeddingModel> {
        calls.add("listEmbeddingModels")
        if (shouldFail) throw ApiException(500, "Server error")
        return embeddingModels.toList()
    }
}
