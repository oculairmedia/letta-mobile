package com.letta.mobile.ui.screens.lettabot

import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.screens.settings.ClientModeConnectionState
import com.letta.mobile.ui.screens.settings.ClientModeConnectionTester
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
class LettaBotConnectionViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var connectionTester: ClientModeConnectionTester
    private lateinit var viewModel: LettaBotConnectionViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        settingsRepository = mockk(relaxed = true)
        connectionTester = mockk(relaxed = true)

        every { settingsRepository.observeClientModeEnabled() } returns flowOf(false)
        every { settingsRepository.observeClientModeBaseUrl() } returns flowOf("")
        every { settingsRepository.getClientModeApiKey() } returns null

        viewModel = LettaBotConnectionViewModel(
            settingsRepository = settingsRepository,
            connectionTester = connectionTester,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads existing client mode settings into state`() = runTest {
        every { settingsRepository.observeClientModeEnabled() } returns flowOf(true)
        every { settingsRepository.observeClientModeBaseUrl() } returns flowOf("http://192.168.50.90:8407")
        every { settingsRepository.getClientModeApiKey() } returns "stored-key"

        viewModel = LettaBotConnectionViewModel(
            settingsRepository = settingsRepository,
            connectionTester = connectionTester,
        )

        val state = awaitSuccessState()
        assertEquals(true, state.enabled)
        assertEquals("http://192.168.50.90:8407", state.baseUrl)
        assertEquals("stored-key", state.apiKey)
    }

    @Test
    fun `save persists current values through settings repository`() = runTest {
        viewModel.setEnabled(true)
        viewModel.setBaseUrl("http://192.168.50.90:8407")
        viewModel.setApiKey("new-key")

        var savedSuccessfully = false
        viewModel.save(onSuccess = { savedSuccessfully = true })

        assertTrue(savedSuccessfully)
        coVerify(exactly = 1) { settingsRepository.setClientModeEnabled(true) }
        coVerify(exactly = 1) { settingsRepository.setClientModeBaseUrl("http://192.168.50.90:8407") }
        coVerify(exactly = 1) { settingsRepository.setClientModeApiKey("new-key") }
    }

    @Test
    fun `save trims and nulls blank api key`() = runTest {
        viewModel.setEnabled(true)
        viewModel.setBaseUrl("  http://example.com  ")
        viewModel.setApiKey("   ")

        viewModel.save()

        coVerify(exactly = 1) { settingsRepository.setClientModeBaseUrl("http://example.com") }
        coVerify(exactly = 1) { settingsRepository.setClientModeApiKey(null) }
    }

    @Test
    fun `testConnection emits success state when tester succeeds`() = runTest {
        coEvery {
            connectionTester.test(
                baseUrl = "http://192.168.50.90:8407",
                apiKey = "secret-key",
            )
        } returns Result.success(Unit)

        viewModel.setEnabled(true)
        viewModel.setBaseUrl("http://192.168.50.90:8407")
        viewModel.setApiKey("secret-key")
        viewModel.testConnection()

        val state = awaitSuccessState()
        assertTrue(state.connectionState is ClientModeConnectionState.Success)
    }

    @Test
    fun `testConnection surfaces missing url immediately`() = runTest {
        viewModel.setEnabled(true)
        viewModel.setBaseUrl("")
        viewModel.testConnection()

        val state = awaitSuccessState()
        assertTrue(state.connectionState is ClientModeConnectionState.Failure)
    }

    private suspend fun awaitSuccessState(): LettaBotConnectionUiState {
        return viewModel.uiState.first { it is UiState.Success }.let { (it as UiState.Success).data }
    }
}
