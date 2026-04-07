package com.letta.mobile.ui.screens.models

import app.cash.turbine.test
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.ModelRepository
import com.letta.mobile.ui.common.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelBrowserViewModelTest {

    private lateinit var fakeRepo: FakeModelRepo
    private lateinit var viewModel: ModelBrowserViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeModelRepo()
        viewModel = ModelBrowserViewModel(fakeRepo)
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `loadModels sets Success with models`() = runTest {
        fakeRepo.setModels(listOf(
            LlmModel(id = "m1", name = "GPT-4o", providerType = "openai", contextWindow = 128000),
            LlmModel(id = "m2", name = "Claude", providerType = "anthropic", contextWindow = 200000),
        ))
        viewModel.loadModels()
        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertEquals(2, state.data.models.size)
        }
    }

    @Test
    fun `getProviders returns distinct sorted providers`() = runTest {
        fakeRepo.setModels(listOf(
            LlmModel(id = "1", name = "A", providerType = "openai"),
            LlmModel(id = "2", name = "B", providerType = "anthropic"),
            LlmModel(id = "3", name = "C", providerType = "openai"),
        ))
        viewModel.loadModels()
        val providers = viewModel.getProviders()
        assertEquals(listOf("anthropic", "openai"), providers)
    }

    @Test
    fun `getFilteredModels filters by search query`() = runTest {
        fakeRepo.setModels(listOf(
            LlmModel(id = "1", name = "GPT-4o", providerType = "openai"),
            LlmModel(id = "2", name = "Claude", providerType = "anthropic"),
        ))
        viewModel.loadModels()
        viewModel.updateSearchQuery("GPT")
        val filtered = viewModel.getFilteredModels()
        assertEquals(1, filtered.size)
        assertEquals("GPT-4o", filtered.first().name)
    }

    @Test
    fun `getFilteredModels filters by provider`() = runTest {
        fakeRepo.setModels(listOf(
            LlmModel(id = "1", name = "GPT-4o", providerType = "openai"),
            LlmModel(id = "2", name = "Claude", providerType = "anthropic"),
        ))
        viewModel.loadModels()
        viewModel.selectProvider("anthropic")
        val filtered = viewModel.getFilteredModels()
        assertEquals(1, filtered.size)
        assertEquals("Claude", filtered.first().name)
    }

    @Test
    fun `loadModels sets Error on failure`() = runTest {
        fakeRepo.shouldFail = true
        viewModel.loadModels()
        viewModel.uiState.test { assertTrue(awaitItem() is UiState.Error) }
    }

    private class FakeModelRepo : ModelRepository(null!!) {
        private val _llm = MutableStateFlow<List<LlmModel>>(emptyList())
        private val _emb = MutableStateFlow<List<EmbeddingModel>>(emptyList())
        override val llmModels: StateFlow<List<LlmModel>> = _llm.asStateFlow()
        override val embeddingModels: StateFlow<List<EmbeddingModel>> = _emb.asStateFlow()
        var shouldFail = false

        fun setModels(models: List<LlmModel>) { _llm.value = models }
        override suspend fun refreshLlmModels() { if (shouldFail) throw Exception("Failed") }
        override suspend fun refreshEmbeddingModels() { if (shouldFail) throw Exception("Failed") }
    }
}
