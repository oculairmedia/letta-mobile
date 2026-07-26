package com.letta.mobile.ui.screens.providers

import com.letta.mobile.data.model.Provider
import com.letta.mobile.data.model.ProviderCheckParams
import com.letta.mobile.data.model.ProviderCreateParams
import com.letta.mobile.data.model.ProviderId
import com.letta.mobile.data.model.ProviderUpdateParams
import com.letta.mobile.data.repository.ProviderRepository
import com.letta.mobile.data.repository.api.IProviderRepository
import com.letta.mobile.testutil.FakeProviderApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun `refresh keeps cached content visible until replacement arrives`() = runTest {
        val initialProviders = fakeApi.providers.toList()
        val replacementProviders = initialProviders + Provider(
            id = ProviderId("provider-3"),
            name = "Local",
            providerType = "openai-compatible",
        )
        val controlledRepository = ControlledProviderRepository(initialProviders)
        val controlledViewModel = ProviderAdminViewModel(controlledRepository)
        val refreshGate = controlledRepository.pauseNextRefresh(replacementProviders)
        controlledViewModel.updateSearchQuery("open")

        controlledViewModel.loadProviders()

        val refreshing = controlledViewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertTrue(refreshing.data.isRefreshing)
        assertEquals(initialProviders, refreshing.data.providers)
        assertEquals("open", refreshing.data.searchQuery)

        refreshGate.complete(Unit)

        val refreshed = controlledViewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertFalse(refreshed.data.isRefreshing)
        assertEquals(replacementProviders, refreshed.data.providers)
        assertEquals("open", refreshed.data.searchQuery)
    }

    @Test
    fun `refresh failure keeps cached content and exposes lightweight error`() = runTest {
        viewModel.updateSearchQuery("anthropic")
        fakeApi.shouldFail = true

        viewModel.loadProviders()

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertFalse(state.data.isRefreshing)
        assertEquals(2, state.data.providers.size)
        assertEquals("anthropic", state.data.searchQuery)
        assertNotNull(state.data.operationError)
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
    fun `inspectProvider opens cached details before remote details arrive`() = runTest {
        val cached = fakeApi.providers.first()
        val detailed = cached.copy(providerCategory = "cloud")
        val controlledRepository = ControlledProviderRepository(fakeApi.providers.toList())
        val controlledViewModel = ProviderAdminViewModel(controlledRepository)
        val inspectionGate = controlledRepository.pauseNextInspection(detailed)

        controlledViewModel.inspectProvider(cached.id!!)

        val inspecting = controlledViewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals(cached, inspecting.data.selectedProvider)
        assertEquals(cached.id, inspecting.data.inspectingProviderId)

        inspectionGate.complete(Unit)

        val inspected = controlledViewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals(detailed, inspected.data.selectedProvider)
        assertEquals(null, inspected.data.inspectingProviderId)
        assertEquals(detailed, inspected.data.providers.first())
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

private class ControlledProviderRepository(
    initialProviders: List<Provider>,
) : IProviderRepository {
    private val mutableProviders = MutableStateFlow(initialProviders)
    override val providers: StateFlow<List<Provider>> = mutableProviders

    private var refreshGate: CompletableDeferred<Unit>? = null
    private var refreshResult: List<Provider>? = null
    private var inspectionGate: CompletableDeferred<Unit>? = null
    private var inspectionResult: Provider? = null

    fun pauseNextRefresh(result: List<Provider>): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also {
            refreshResult = result
            refreshGate = it
        }

    fun pauseNextInspection(result: Provider): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also {
            inspectionResult = result
            inspectionGate = it
        }

    override suspend fun refreshProviders(name: String?, providerType: String?) {
        refreshGate?.await()
        refreshResult?.let { mutableProviders.value = it }
        refreshGate = null
        refreshResult = null
    }

    override suspend fun getProvider(providerId: ProviderId): Provider {
        inspectionGate?.await()
        return inspectionResult
            ?: mutableProviders.value.first { it.id == providerId }
    }

    override suspend fun createProvider(params: ProviderCreateParams): Provider =
        error("Not used in this test")

    override suspend fun updateProvider(providerId: ProviderId, params: ProviderUpdateParams): Provider =
        error("Not used in this test")

    override suspend fun checkProvider(params: ProviderCheckParams) =
        error("Not used in this test")

    override suspend fun checkExistingProvider(providerId: ProviderId) =
        error("Not used in this test")

    override suspend fun deleteProvider(providerId: ProviderId) =
        error("Not used in this test")
}
