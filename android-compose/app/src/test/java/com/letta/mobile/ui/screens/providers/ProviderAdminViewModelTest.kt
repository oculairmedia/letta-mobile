package com.letta.mobile.ui.screens.providers

import com.letta.mobile.data.model.Provider
import com.letta.mobile.data.model.ProviderId
import com.letta.mobile.data.repository.ProviderRepository
import com.letta.mobile.testutil.FakeProviderApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class ProviderAdminViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeApi: FakeProviderApi
    private lateinit var repository: ProviderRepository
    private lateinit var viewModel: ProviderAdminViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeApi = FakeProviderApi()
        fakeApi.providers.addAll(
            listOf(
                Provider(id = ProviderId("provider-1"), name = "OpenAI", providerType = "openai", baseUrl = "https://api.openai.com", region = "us"),
                Provider(id = ProviderId("provider-2"), name = "Anthropic", providerType = "anthropic", baseUrl = "https://api.anthropic.com", region = "global"),
            )
        )
        repository = ProviderRepository(fakeApi)
        viewModel = ProviderAdminViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadProviders populates state`() = runTest {
        viewModel.loadProviders()

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals(2, state.data.providers.size)
    }

    @Test
    fun `updateSearchQuery filters providers locally`() = runTest {
        viewModel.loadProviders()
        viewModel.updateSearchQuery("anthropic")

        val filtered = viewModel.getFilteredProviders()
        assertEquals(1, filtered.size)
        assertEquals(ProviderId("provider-2"), filtered.first().id)
    }

    @Test
    fun `inspectProvider loads provider details`() = runTest {
        viewModel.inspectProvider(ProviderId("provider-1"))

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals(ProviderId("provider-1"), state.data.selectedProvider?.id)
    }

    @Test
    fun `checkProvider delegates to repository`() = runTest {
        viewModel.checkProvider(ProviderId("provider-1"))

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertTrue(fakeApi.calls.contains("checkExistingProvider:provider-1"))
        assertEquals("Provider check succeeded", state.data.operationMessage)
    }

    @Test
    fun `deleteProvider removes provider`() = runTest {
        viewModel.deleteProvider(ProviderId("provider-1"))

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals(1, state.data.providers.size)
    }
}
