package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.bound
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.api.ModelIrohSource
import com.letta.mobile.data.repository.api.ModelRemoteSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [CachedModelRepository]'s refresh contract, restored from the
 * CachedModelAndMcpServerRepositoryTest deleted with the dead write stack (1c8826a5f5): a
 * refresh replaces the catalog rather than accumulating rows, the Iroh source answers alone
 * while it is active, and the embedding catalog still falls back to the remote source.
 */
class CachedModelRepositoryTest {

    @Test
    fun refreshLlmModelsUsesIrohSourceWhenActive() = runTest {
        val remote = FakeModelRemoteSource(llmModels = listOf(model("remote/model")))
        val iroh = FakeModelIrohSource(llmModels = listOf(model("iroh/model")))
        val repository = CachedModelRepository(remote.bound(), irohModelSource = iroh)

        repository.refreshLlmModels()

        assertEquals(listOf("iroh/model"), repository.llmModels.value.map { it.handle })
        assertTrue(remote.llmCalls == 0)
    }

    @Test
    fun refreshLlmModelsReplacesPriorSnapshotAndDoesNotAccumulateDuplicates() = runTest {
        val duplicate = model("openai/gpt-4o")
        val remote = FakeModelRemoteSource(
            llmModels = listOf(duplicate, duplicate, model("anthropic/claude")),
        )
        val repository = CachedModelRepository(remote.bound())

        repository.refreshLlmModels()
        assertEquals(
            listOf("openai/gpt-4o", "anthropic/claude"),
            repository.llmModels.value.map { it.handle },
        )

        remote.llmModels = listOf(model("google/gemini"))
        repository.refreshLlmModels()

        assertEquals(listOf("google/gemini"), repository.llmModels.value.map { it.handle })
    }

    @Test
    fun refreshEmbeddingModelsUsesRemoteWhenIrohIsNotActive() = runTest {
        val remote = FakeModelRemoteSource(
            embeddingModels = listOf(EmbeddingModel(id = "embed-1", name = "Embed", handle = "openai/embed")),
        )
        val repository = CachedModelRepository(remote.bound())

        repository.refreshEmbeddingModels()

        assertEquals(listOf("openai/embed"), repository.embeddingModels.value.map { it.handle })
        assertEquals(1, remote.embeddingCalls)
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

    private class FakeModelRemoteSource(
        var llmModels: List<LlmModel> = emptyList(),
        private val embeddingModels: List<EmbeddingModel> = emptyList(),
    ) : ModelRemoteSource {
        var llmCalls = 0
        var embeddingCalls = 0

        override suspend fun listLlmModels(): List<LlmModel> {
            llmCalls += 1
            return llmModels
        }

        override suspend fun listEmbeddingModels(): List<EmbeddingModel> {
            embeddingCalls += 1
            return embeddingModels
        }
    }

    private class FakeModelIrohSource(
        private val llmModels: List<LlmModel> = emptyList(),
        private val embeddingModels: List<EmbeddingModel> = emptyList(),
    ) : ModelIrohSource {
        override fun shouldUseIroh(): Boolean = true

        override suspend fun listLlmModels(): List<LlmModel> = llmModels

        override suspend fun listEmbeddingModels(): List<EmbeddingModel> = embeddingModels
    }
}
