package com.letta.mobile.data.repository

import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.testutil.FakeModelApi
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun httpCatalogKeepsOnlyCredentialedProvidersWhenLoaderPresent() = runTest {
        val api = FakeModelApi().apply {
            llmModels += model("openai/gpt-4o")
            llmModels += model("anthropic/claude-sonnet")
        }
        val repository = ModelRepository(
            modelApi = api,
            credentialedProviderTypes = { setOf("openai") },
        )

        repository.refreshLlmModels()

        assertEquals(listOf("openai/gpt-4o"), repository.llmModels.value.map { it.handle })
    }

    @Test
    fun irohCatalogKeepsOnlyCredentialedProvidersWhenLoaderPresent() = runTest {
        val irohSource = mockk<IrohAdminRpcModelSource>().apply {
            every { shouldUseIroh() } returns true
            coEvery { listLlmModels() } returns listOf(
                model("openai/gpt-4o"),
                model("anthropic/claude-sonnet"),
            )
        }
        val repository = ModelRepository(
            modelApi = FakeModelApi(),
            irohModelSource = irohSource,
            credentialedProviderTypes = { setOf("anthropic") },
        )

        repository.refreshLlmModels()

        assertEquals(listOf("anthropic/claude-sonnet"), repository.llmModels.value.map { it.handle })
    }

    @Test
    fun emptyCredentialedSetLeavesCatalogUnchanged() = runTest {
        val api = FakeModelApi().apply {
            llmModels += model("openai/gpt-4o")
            llmModels += model("anthropic/claude-sonnet")
        }
        val repository = ModelRepository(
            modelApi = api,
            credentialedProviderTypes = { emptySet() },
        )

        repository.refreshLlmModels()

        assertEquals(
            setOf("openai/gpt-4o", "anthropic/claude-sonnet"),
            repository.llmModels.value.map { it.handle }.toSet(),
        )
    }

    @Test
    fun absentLoaderLeavesCatalogUnchanged() = runTest {
        val api = FakeModelApi().apply {
            llmModels += model("openai/gpt-4o")
            llmModels += model("anthropic/claude-sonnet")
        }
        val repository = ModelRepository(modelApi = api)

        repository.refreshLlmModels()

        assertEquals(
            setOf("openai/gpt-4o", "anthropic/claude-sonnet"),
            repository.llmModels.value.map { it.handle }.toSet(),
        )
    }

    @Test
    fun throwingCredentialLoaderKeepsCatalogUnchanged() = runTest {
        val api = FakeModelApi().apply {
            llmModels += model("openai/gpt-4o")
            llmModels += model("anthropic/claude-sonnet")
        }
        val repository = ModelRepository(
            modelApi = api,
            credentialedProviderTypes = { throw IllegalStateException("provider lookup unavailable") },
        )

        repository.refreshLlmModels()

        assertEquals(
            setOf("openai/gpt-4o", "anthropic/claude-sonnet"),
            repository.llmModels.value.map { it.handle }.toSet(),
        )
    }

    @Test
    fun credentialLoaderCancellationPropagates() {
        val api = FakeModelApi().apply {
            llmModels += model("openai/gpt-4o")
        }
        val repository = ModelRepository(
            modelApi = api,
            credentialedProviderTypes = { throw CancellationException("provider lookup cancelled") },
        )

        assertThrows(CancellationException::class.java) {
            runBlocking { repository.refreshLlmModels() }
        }
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
