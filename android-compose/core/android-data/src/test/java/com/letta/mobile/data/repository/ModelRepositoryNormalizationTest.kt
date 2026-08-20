package com.letta.mobile.data.repository

import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.testutil.FakeModelApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelRepositoryNormalizationTest {
    @Test
    fun httpCatalogUsesSharedLlmuxNormalization() = runTest {
        val api = FakeModelApi().apply {
            llmModels += model("lmstudio/MiniMax-M3")
            llmModels += model("openai/MiniMax-M3")
        }
        val repository = ModelRepository(modelApi = api)

        repository.refreshLlmModels()

        val model = repository.llmModels.value.single()
        assertEquals("openai/MiniMax-M3", model.handle)
        assertEquals(200_000, model.contextWindow)
        assertEquals(16_384, model.maxOutputTokens)
        assertEquals(setOf("lmstudio/MiniMax-M3"), model.selectionAliases)
    }

    private fun model(handle: String): LlmModel {
        val provider = handle.substringBefore('/')
        return LlmModel(
            id = handle,
            name = handle.substringAfter('/'),
            handle = handle,
            providerType = provider,
        )
    }
}
