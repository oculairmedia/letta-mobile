package com.letta.mobile.ui.screens.models

import app.cash.turbine.test
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.ModelRepository
import com.letta.mobile.ui.common.UiState
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
        val state = awaitSuccessState()
        assertEquals(2, state.models.size)
    }

    @Test
    fun `getProviders returns distinct sorted providers`() = runTest {
        fakeRepo.setModels(listOf(
            LlmModel(id = "1", name = "A", providerType = "openai"),
            LlmModel(id = "2", name = "B", providerType = "anthropic"),
            LlmModel(id = "3", name = "C", providerType = "openai"),
        ))
        viewModel.loadModels()
        awaitSuccessState()
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
        awaitSuccessState()
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
        awaitSuccessState()
        viewModel.selectProvider("anthropic")
        val filtered = viewModel.getFilteredModels()
        assertEquals(1, filtered.size)
        assertEquals("Claude", filtered.first().name)
    }

    @Test
    fun `loadModels sets Error on failure`() = runTest {
        fakeRepo.shouldFail = true
        viewModel.loadModels()
        viewModel.uiState.test {
            assertTrue(awaitItem() is UiState.Error)
        }
    }

    @Test
    fun `loadModels loads embedding models`() = runTest {
        fakeRepo.setEmbeddings(listOf(
            EmbeddingModel(id = "e1", name = "text-embedding-3-small", providerType = "openai"),
            EmbeddingModel(id = "e2", name = "voyage-3", providerType = "voyageai"),
        ))
        viewModel.loadModels()
        val state = awaitSuccessState()
        assertEquals(2, state.embeddingModels.size)
    }

    @Test
    fun `selectTab switches to embedding and clears provider`() = runTest {
        fakeRepo.setModels(listOf(LlmModel(id = "1", name = "A", providerType = "openai")))
        fakeRepo.setEmbeddings(listOf(EmbeddingModel(id = "e1", name = "B", providerType = "voyageai")))
        viewModel.loadModels()
        awaitSuccessState()
        viewModel.selectProvider("openai")
        viewModel.selectTab(ModelTab.EMBEDDING)
        val state = awaitSuccessState()
        assertEquals(ModelTab.EMBEDDING, state.selectedTab)
        assertEquals(null, state.selectedProvider)
    }

    @Test
    fun `getProviders returns embedding providers on embedding tab`() = runTest {
        fakeRepo.setModels(listOf(LlmModel(id = "1", name = "A", providerType = "openai")))
        fakeRepo.setEmbeddings(listOf(
            EmbeddingModel(id = "e1", name = "B", providerType = "voyageai"),
            EmbeddingModel(id = "e2", name = "C", providerType = "openai"),
        ))
        viewModel.loadModels()
        awaitSuccessState()
        viewModel.selectTab(ModelTab.EMBEDDING)
        val providers = viewModel.getProviders()
        assertEquals(listOf("openai", "voyageai"), providers)
    }

    @Test
    fun `getFilteredEmbeddingModels filters by search`() = runTest {
        fakeRepo.setEmbeddings(listOf(
            EmbeddingModel(id = "e1", name = "text-embedding-3-small", providerType = "openai"),
            EmbeddingModel(id = "e2", name = "voyage-3", providerType = "voyageai"),
        ))
        viewModel.loadModels()
        awaitSuccessState()
        viewModel.updateSearchQuery("voyage")
        val filtered = viewModel.getFilteredEmbeddingModels()
        assertEquals(1, filtered.size)
        assertEquals("voyage-3", filtered.first().name)
    }

    @Test
    fun `getFilteredEmbeddingModels filters by provider`() = runTest {
        fakeRepo.setEmbeddings(listOf(
            EmbeddingModel(id = "e1", name = "text-embedding-3-small", providerType = "openai"),
            EmbeddingModel(id = "e2", name = "voyage-3", providerType = "voyageai"),
        ))
        viewModel.loadModels()
        awaitSuccessState()
        viewModel.selectProvider("openai")
        val filtered = viewModel.getFilteredEmbeddingModels()
        assertEquals(1, filtered.size)
        assertEquals("text-embedding-3-small", filtered.first().name)
    }

    @Test
    fun `selectLlmModel and clearSelectedLlmModel work`() = runTest {
        val model = LlmModel(id = "m1", name = "GPT-4o", providerType = "openai")
        fakeRepo.setModels(listOf(model))
        viewModel.loadModels()
        awaitSuccessState()
        viewModel.selectLlmModel(model)
        assertEquals(model, awaitSuccessState().selectedLlmModel)
        viewModel.clearSelectedLlmModel()
        assertEquals(null, awaitSuccessState().selectedLlmModel)
    }

    @Test
    fun `selectEmbeddingModel and clearSelectedEmbeddingModel work`() = runTest {
        val model = EmbeddingModel(id = "e1", name = "embed-v3", providerType = "cohere")
        fakeRepo.setEmbeddings(listOf(model))
        viewModel.loadModels()
        awaitSuccessState()
        viewModel.selectEmbeddingModel(model)
        assertEquals(model, awaitSuccessState().selectedEmbeddingModel)
        viewModel.clearSelectedEmbeddingModel()
        assertEquals(null, awaitSuccessState().selectedEmbeddingModel)
    }

    @Test
    fun `search matches on handle field`() = runTest {
        fakeRepo.setModels(listOf(
            LlmModel(id = "1", name = "GPT-4o", providerType = "openai", handle = "letta/gpt-4o"),
            LlmModel(id = "2", name = "Claude", providerType = "anthropic", handle = "letta/claude-3-5-sonnet"),
        ))
        viewModel.loadModels()
        awaitSuccessState()
        viewModel.updateSearchQuery("sonnet")
        val filtered = viewModel.getFilteredModels()
        assertEquals(1, filtered.size)
        assertEquals("Claude", filtered.first().name)
    }

    private suspend fun awaitSuccessState(): ModelBrowserUiState {
        return viewModel.uiState.first { it is UiState.Success }.let { (it as UiState.Success).data }
    }

    private class FakeModelRepo : ModelRepository(mockk(relaxed = true)) {
        private val _llm = MutableStateFlow<List<LlmModel>>(emptyList())
        private val _emb = MutableStateFlow<List<EmbeddingModel>>(emptyList())
        override val llmModels: StateFlow<List<LlmModel>> = _llm.asStateFlow()
        override val embeddingModels: StateFlow<List<EmbeddingModel>> = _emb.asStateFlow()
        var shouldFail = false

        fun setModels(models: List<LlmModel>) { _llm.value = models }
        fun setEmbeddings(models: List<EmbeddingModel>) { _emb.value = models }
        override suspend fun refreshLlmModels() { if (shouldFail) throw Exception("Failed") }
        override suspend fun refreshEmbeddingModels() { if (shouldFail) throw Exception("Failed") }
    }
}
