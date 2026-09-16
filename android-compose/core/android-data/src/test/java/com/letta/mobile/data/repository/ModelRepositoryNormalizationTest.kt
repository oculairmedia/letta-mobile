package com.letta.mobile.data.repository

import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.testutil.FakeModelApi
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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

    @Test
    fun httpCatalogRetainsAllModelsWithoutProviderFiltering() = runTest {
        val repository = ModelRepository(modelApi = defaultCatalogApi())

        repository.refreshLlmModels()

        assertEquals(defaultExpectedHandles, repository.llmModels.value.map { it.handle }.toSet())
    }

    @Test
    fun irohCatalogRetainsAllModelsWithoutProviderFiltering() = runTest {
        val irohSource = mockk<IrohAdminRpcModelSource>().apply {
            every { shouldUseIroh() } returns true
            coEvery { listLlmModels() } returns listOf(
                model("openai/gpt-4o"),
                model("anthropic/claude-sonnet"),
                model("google/gemini-1.5-pro"),
            )
        }
        val repository = ModelRepository(
            modelApi = FakeModelApi(),
            irohModelSource = irohSource,
        )

        repository.refreshLlmModels()

        assertEquals(
            setOf("openai/gpt-4o", "anthropic/claude-sonnet", "google/gemini-1.5-pro"),
            repository.llmModels.value.map { it.handle }.toSet(),
        )
    }

    private val defaultExpectedHandles = setOf("openai/gpt-4o", "anthropic/claude-sonnet")

    private fun defaultCatalogApi(): FakeModelApi = FakeModelApi().apply {
        llmModels += model("openai/gpt-4o")
        llmModels += model("anthropic/claude-sonnet")
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
