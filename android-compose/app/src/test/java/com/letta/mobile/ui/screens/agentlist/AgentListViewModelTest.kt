package com.letta.mobile.ui.screens.agentlist

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentImportParams
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import kotlinx.collections.immutable.persistentListOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

/** The agent list's view model; creating an agent is AndroidAgentCreator's (AndroidAgentCreatorTest). */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class AgentListViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var agentRepository: IAgentRepository
    private lateinit var settingsRepository: ISettingsRepository
    private val activeConfigFlow = MutableStateFlow<LettaConfig?>(null)
    private lateinit var viewModel: AgentListViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        agentRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        activeConfigFlow.value = null

        every { agentRepository.agents } returns MutableStateFlow(emptyList())
        every { settingsRepository.activeConfig } returns activeConfigFlow
        every { settingsRepository.activeConfigChanges } returns emptyFlow()
        every { settingsRepository.favoriteAgentId } returns MutableStateFlow(null)
        every { settingsRepository.getPinnedAgentIds() } returns MutableStateFlow(emptySet())

        viewModel = newViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `importAgent forwards safety flags and returns imported ids`() = runTest {
        coEvery {
            agentRepository.importAgent(match { it.matchesImportSafetyFlags() })
        } returns ImportedAgentsResponse(agentIds = listOf("a2"))

        var importedIds: List<String> = emptyList()
        viewModel.importAgent(
            fileName = "agent.json",
            fileBytes = "{}".toByteArray(),
            overrideName = "Cloned Agent",
            overrideExistingTools = false,
            stripMessages = true,
        ) { importedIds = it.agentIds }

        assertEquals(listOf("a2"), importedIds)
        coVerify(exactly = 1) {
            agentRepository.importAgent(match { it.matchesImportSafetyFlags() })
        }
    }

    private fun AgentImportParams.matchesImportSafetyFlags(): Boolean =
        fileName == "agent.json" &&
            overrideName == "Cloned Agent" &&
            overrideExistingTools == false &&
            projectId == null &&
            stripMessages == true

    @Test
    fun `getAllTags returns sorted distinct tags from all agents`() = runTest {
        viewModel = viewModelWith(
            Agent(id = AgentId("a1"), name = "Agent1", tags = persistentListOf("beta", "alpha")),
            Agent(id = AgentId("a2"), name = "Agent2", tags = persistentListOf("alpha", "gamma")),
            Agent(id = AgentId("a3"), name = "Agent3", tags = persistentListOf()),
        )

        val tags = viewModel.getAllTags()
        assertEquals(listOf("alpha", "beta", "gamma"), tags)
    }

    @Test
    fun `getAllTags returns empty list when no agents have tags`() = runTest {
        viewModel = viewModelWith(Agent(id = AgentId("a1"), name = "Agent1"), Agent(id = AgentId("a2"), name = "Agent2"))

        assertTrue(viewModel.getAllTags().isEmpty())
    }

    /** A view model over a repository holding [agents]. */
    private fun TestScope.viewModelWith(vararg agents: Agent): AgentListViewModel {
        every { agentRepository.agents } returns MutableStateFlow(agents.toList())
        return watched()
    }

    /** A view model whose state is watched, as the screen watches it (the state is shared while subscribed). */
    private fun TestScope.watched(): AgentListViewModel =
        newViewModel().also { vm -> backgroundScope.launch(testDispatcher) { vm.uiState.collect {} } }

    @Test
    fun `toggleTag adds tag to selectedTags`() = runTest {
        viewModel = watched()
        viewModel.toggleTag("alpha")
        assertEquals(setOf("alpha"), viewModel.uiState.value.selectedTags)
    }

    @Test
    fun `toggleTag removes tag when already selected`() = runTest {
        viewModel = watched()
        viewModel.toggleTag("alpha")
        viewModel.toggleTag("alpha")
        assertTrue(viewModel.uiState.value.selectedTags.isEmpty())
    }

    @Test
    fun `toggleTag supports multi-select`() = runTest {
        viewModel = watched()
        viewModel.toggleTag("alpha")
        viewModel.toggleTag("beta")
        assertEquals(setOf("alpha", "beta"), viewModel.uiState.value.selectedTags)
    }

    @Test
    fun `clearTags resets selectedTags to empty`() = runTest {
        viewModel = watched()
        viewModel.toggleTag("alpha")
        viewModel.toggleTag("beta")
        viewModel.clearTags()
        assertTrue(viewModel.uiState.value.selectedTags.isEmpty())
    }

    @Test
    fun `getFilteredAgents filters by selected tags with AND logic`() = runTest {
        viewModel = viewModelWith(
            Agent(id = AgentId("a1"), name = "Agent1", tags = persistentListOf("alpha", "beta")),
            Agent(id = AgentId("a2"), name = "Agent2", tags = persistentListOf("alpha")),
            Agent(id = AgentId("a3"), name = "Agent3", tags = persistentListOf("beta", "gamma")),
        )

        // No filter — all agents
        assertEquals(3, viewModel.getFilteredAgents().size)

        // Single tag filter
        viewModel.toggleTag("alpha")
        val alphaFiltered = viewModel.getFilteredAgents()
        assertEquals(2, alphaFiltered.size)
        assertTrue(alphaFiltered.any { it.id.value == "a1" })
        assertTrue(alphaFiltered.any { it.id.value == "a2" })

        // AND logic: alpha + beta — only a1 has both
        viewModel.toggleTag("beta")
        val andFiltered = viewModel.getFilteredAgents()
        assertEquals(1, andFiltered.size)
        assertEquals("a1", andFiltered.first().id.value)
    }

    @Test
    fun `getFilteredAgents combines tag filter with search query`() = runTest {
        viewModel = viewModelWith(
            Agent(id = AgentId("a1"), name = "ChatBot", tags = persistentListOf("production")),
            Agent(id = AgentId("a2"), name = "HelperBot", tags = persistentListOf("production")),
            Agent(id = AgentId("a3"), name = "TestBot", tags = persistentListOf("staging")),
        )

        viewModel.toggleTag("production")
        viewModel.updateSearchQuery("Chat")

        val results = viewModel.getFilteredAgents()
        assertEquals(1, results.size)
        assertEquals("a1", results.first().id.value)
    }

    @Test
    fun `getFilteredAgents returns all when no tags selected and no search`() = runTest {
        viewModel = viewModelWith(Agent(id = AgentId("a1"), name = "Agent1", tags = persistentListOf("alpha")), Agent(id = AgentId("a2"), name = "Agent2"))

        assertEquals(2, viewModel.getFilteredAgents().size)
    }

    @Test
    fun `empty local runtime mode settles`() = runTest {
        clearMocks(agentRepository, answers = false, recordedCalls = true)
        activeConfigFlow.value = localConfig()
        every { agentRepository.agents } returns MutableStateFlow(emptyList())
        viewModel = watched()

        assertTrue(viewModel.uiState.value.agents.isEmpty())
        assertFalse(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.isHydrating)
    }

    @Test
    fun `local runtime mode refreshes through the repository routing`() = runTest {
        // letta-mobile-y5c9u: the no-remote-fetch invariant moved into
        // AgentRepository (it lists from the on-device store in local mode),
        // so the ViewModel refreshes unconditionally — local agents must
        // repopulate after the per-session Room wipe.
        clearMocks(agentRepository, answers = false, recordedCalls = true)
        activeConfigFlow.value = localConfig()
        every { agentRepository.agents } returns MutableStateFlow(emptyList())
        coEvery { agentRepository.refreshAgents() } returns Unit

        viewModel = watched()
        viewModel.refresh()

        assertFalse(viewModel.uiState.value.isLoading)
        coVerify(exactly = 1) { agentRepository.refreshAgents() }
    }

    @Test
    fun `remote runtime mode displays all agents and refreshes normally`() = runTest {
        clearMocks(agentRepository, answers = false, recordedCalls = true)
        activeConfigFlow.value = remoteConfig()
        every { agentRepository.agents } returns MutableStateFlow(
            listOf(
                Agent(id = AgentId("remote-agent"), name = "Remote"),
                Agent(id = AgentId("local-agent-generated"), name = "Local id"),
            )
        )
        coEvery { agentRepository.refreshAgentsIfStale(any()) } returns false

        viewModel = watched()

        assertEquals(listOf("remote-agent", "local-agent-generated"), viewModel.uiState.value.agents.map { it.id.value })
        coVerify(exactly = 1) { agentRepository.refreshAgentsIfStale(any()) }
    }

    @Test
    fun `loadAgents shows first hydrated page while refresh continues`() = runTest {
        val agentsFlow = MutableStateFlow<List<Agent>>(emptyList())
        val keepRefreshing = CompletableDeferred<Unit>()
        every { agentRepository.agents } returns agentsFlow
        coEvery { agentRepository.refreshAgentsIfStale(any()) } coAnswers {
            agentsFlow.value = listOf(Agent(id = AgentId("a1"), name = "Needle Agent"))
            keepRefreshing.await()
            true
        }

        viewModel = watched()

        assertEquals(listOf("a1"), viewModel.uiState.value.agents.map { it.id.value })
        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.isHydrating)

        keepRefreshing.complete(Unit)
    }

    private fun newViewModel(): AgentListViewModel = AgentListViewModel(
        agentRepository = agentRepository,
        settingsRepository = settingsRepository,
    )

    private fun localConfig(
        localModelHandle: String? = "google/gemma-test-litert-lm",
        localModelPath: String? = "/models/gemma-test.litertlm",
    ): LettaConfig = LettaConfig(
        id = "local-config",
        mode = LettaConfig.Mode.LOCAL,
        serverUrl = "local-lettacode://embedded",
        localModelHandle = localModelHandle,
        localModelPath = localModelPath,
        localModelRuntime = "litert-lm",
        localModelAccelerator = "gpu",
        localModelMaxTokens = 8192,
    )

    private fun remoteConfig(): LettaConfig = LettaConfig(
        id = "remote-config",
        mode = LettaConfig.Mode.SELF_HOSTED,
        serverUrl = "https://api.letta.com",
    )

    @Test
    fun `deleteAgent removes deleted agent from ui state immediately`() = runTest {
        val agentsFlow = MutableStateFlow(
            listOf(
                Agent(id = AgentId("a1"), name = "Agent1"),
                Agent(id = AgentId("a2"), name = "Agent2"),
            )
        )
        val favFlow = MutableStateFlow<String?>("a1")
        every { agentRepository.agents } returns agentsFlow
        every { settingsRepository.favoriteAgentId } returns favFlow
        every { settingsRepository.setFavoriteAgentId(any()) } answers { favFlow.value = firstArg() }
        coEvery { agentRepository.deleteAgent(AgentId("a1")) } answers {
            agentsFlow.value = agentsFlow.value.filterNot { it.id == AgentId("a1") }
        }
        viewModel = watched()

        viewModel.deleteAgent(AgentId("a1"))

        assertEquals(listOf("a2"), viewModel.uiState.value.agents.map { it.id.value })
        assertEquals(null, viewModel.uiState.value.favoriteAgentId)
        coVerify(exactly = 1) { agentRepository.deleteAgent(AgentId("a1")) }
        io.mockk.verify(exactly = 1) { settingsRepository.setFavoriteAgentId(null) }
    }
}
