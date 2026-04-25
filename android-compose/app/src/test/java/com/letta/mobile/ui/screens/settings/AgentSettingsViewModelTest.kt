package com.letta.mobile.ui.screens.settings

import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.model.ModelSettings
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.ui.common.UiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
class AgentSettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var agentRepository: AgentRepository
    private lateinit var blockRepository: BlockRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var clientModeConnectionTester: ClientModeConnectionTester
    private lateinit var viewModel: AgentSettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        agentRepository = mockk(relaxed = true)
        blockRepository = mockk(relaxed = true)
        messageRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        clientModeConnectionTester = mockk(relaxed = true)

        every { settingsRepository.observeClientModeEnabled() } returns flowOf(false)
        every { settingsRepository.observeClientModeBaseUrl() } returns flowOf("")
        every { settingsRepository.getClientModeApiKey() } returns null

        every { agentRepository.getAgent("a1") } returns flowOf(
            Agent(
                id = "a1",
                name = "Test Agent",
                description = "A test agent",
                model = "letta/letta-free",
                embedding = "openai/text-embedding-3-small",
                tags = listOf("test"),
                system = "Original system",
                enableSleeptime = false,
                agentType = "stateful",
                contextWindowLimit = 128000,
                modelSettings = ModelSettings(
                    temperature = 0.7,
                    maxOutputTokens = 2000,
                    parallelToolCalls = true,
                    providerType = "openai",
                ),
                blocks = listOf(
                    Block(id = "b1", label = "persona", value = "persona value"),
                    Block(id = "b2", label = "human", value = "human value"),
                ),
            ),
        )

        viewModel = AgentSettingsViewModel(
            savedStateHandle = SavedStateHandle(mapOf("agentId" to "a1")),
            agentRepository = agentRepository,
            blockRepository = blockRepository,
            messageRepository = messageRepository,
            settingsRepository = settingsRepository,
            clientModeConnectionTester = clientModeConnectionTester,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `saveSettings persists displayed model settings`() = runTest {
        val paramsSlot = slot<AgentUpdateParams>()
        coEvery { agentRepository.updateAgent(eq("a1"), capture(paramsSlot)) } answers {
            Agent(
                id = "a1",
                name = "Test Agent",
                modelSettings = paramsSlot.captured.modelSettings,
                system = paramsSlot.captured.system,
                enableSleeptime = paramsSlot.captured.enableSleeptime,
            )
        }

        viewModel.updateTemperature(1.2f)
        viewModel.updateMaxTokens(4096)
        viewModel.updateParallelToolCalls(false)
        viewModel.updateSystemPrompt("Updated system")
        viewModel.updateSleeptime(true)
        awaitSuccessState()

        var successCalled = false
        viewModel.saveSettings { successCalled = true }

        assertTrue(successCalled)
        assertEquals("Updated system", paramsSlot.captured.system)
        assertEquals(true, paramsSlot.captured.enableSleeptime)
        assertEquals(1.2, paramsSlot.captured.modelSettings?.temperature!!, 0.0001)
        assertEquals(4096, paramsSlot.captured.modelSettings?.maxOutputTokens)
        assertEquals(false, paramsSlot.captured.modelSettings?.parallelToolCalls)
        assertEquals("openai", paramsSlot.captured.modelSettings?.providerType)
    }

    @Test
    fun `loadSettings includes read only operational fields`() = runTest {
        val state = awaitSuccessState()

        assertEquals("stateful", state.agentType)
        assertEquals(128000, state.contextWindow)
    }

    @Test
    fun `exportAgent returns repository export payload`() = runTest {
        coEvery { agentRepository.exportAgent("a1") } returns "{\"id\":\"a1\"}"

        var exported: String? = null
        viewModel.exportAgent { exported = it }

        assertEquals("{\"id\":\"a1\"}", exported)
    }

    @Test
    fun `resetMessages delegates to repository and calls success`() = runTest {
        var successCalled = false

        viewModel.resetMessages { successCalled = true }

        assertTrue(successCalled)
        coVerify(exactly = 1) { messageRepository.resetMessages("a1") }
    }

    @Test
    fun `cloneAgent exports then imports with override flags`() = runTest {
        coEvery { agentRepository.exportAgent("a1") } returns "{\"agents\":[{\"id\":\"a1\"}]}"
        coEvery {
            agentRepository.importAgent(
                fileName = "Test Agent.json",
                fileBytes = any(),
                overrideName = "Test Agent Copy",
                overrideExistingTools = false,
                projectId = null,
                stripMessages = true,
            )
        } returns ImportedAgentsResponse(agentIds = listOf("copy-1"))

        var importedIds: List<String> = emptyList()
        viewModel.cloneAgent(
            cloneName = "Test Agent Copy",
            overrideExistingTools = false,
            stripMessages = true,
        ) { importedIds = it.agentIds }

        assertEquals(listOf("copy-1"), importedIds)
        coVerify(exactly = 1) { agentRepository.exportAgent("a1") }
        coVerify(exactly = 1) {
            agentRepository.importAgent(
                fileName = "Test Agent.json",
                fileBytes = any(),
                overrideName = "Test Agent Copy",
                overrideExistingTools = false,
                projectId = null,
                stripMessages = true,
            )
        }
    }

    // Client-mode persistence and connection-testing have moved to
    // [com.letta.mobile.ui.screens.lettabot.LettaBotConnectionViewModel] (gb57.9).
    // See LettaBotConnectionViewModelTest for coverage of save / test flows.
    @Test
    fun `saveSettings does not touch global client mode settings`() = runTest {
        val paramsSlot = slot<AgentUpdateParams>()
        coEvery { agentRepository.updateAgent(eq("a1"), capture(paramsSlot)) } answers {
            Agent(
                id = "a1",
                name = "Test Agent",
                modelSettings = paramsSlot.captured.modelSettings,
                system = paramsSlot.captured.system,
                enableSleeptime = paramsSlot.captured.enableSleeptime,
            )
        }

        viewModel.saveSettings()

        coVerify(exactly = 0) { settingsRepository.setClientModeEnabled(any()) }
        coVerify(exactly = 0) { settingsRepository.setClientModeBaseUrl(any()) }
        coVerify(exactly = 0) { settingsRepository.setClientModeApiKey(any()) }
    }

    private suspend fun awaitSuccessState(): AgentSettingsUiState {
        return viewModel.uiState.first { it is UiState.Success }.let { (it as UiState.Success).data }
    }
}
