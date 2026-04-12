package com.letta.mobile.ui.screens.agentlist

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.ModelRepository
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.data.repository.ToolRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

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

@OptIn(ExperimentalCoroutinesApi::class)
class AgentListViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var agentRepository: AgentRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var toolRepository: ToolRepository
    private lateinit var modelRepository: ModelRepository
    private lateinit var viewModel: AgentListViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        agentRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        toolRepository = mockk(relaxed = true)
        modelRepository = mockk(relaxed = true)

        every { agentRepository.agents } returns MutableStateFlow(emptyList())
        every { settingsRepository.favoriteAgentId } returns MutableStateFlow(null)
        every { settingsRepository.getPinnedAgentIds() } returns MutableStateFlow(emptySet())
        every { toolRepository.getTools() } returns MutableStateFlow(
            listOf(
                Tool(id = "t1", name = "tool_one"),
                Tool(id = "t2", name = "tool_two"),
            )
        )
        every { modelRepository.llmModels } returns MutableStateFlow(
            listOf(LlmModel(id = "m1", name = "openai/gpt-4o", providerType = "openai"))
        )
        every { modelRepository.embeddingModels } returns MutableStateFlow(
            listOf(EmbeddingModel(id = "e1", name = "openai/text-embedding-3-small", providerType = "openai"))
        )

        viewModel = AgentListViewModel(agentRepository, settingsRepository, toolRepository, modelRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadAvailableTools populates create form tool source`() = runTest {
        viewModel.loadAvailableTools()

        assertEquals(2, viewModel.uiState.value.availableTools.size)
        assertEquals("tool_one", viewModel.uiState.value.availableTools.first().name)
    }

    @Test
    fun `loadAvailableModels populates create form model sources`() = runTest {
        viewModel.loadAvailableModels()

        assertEquals(1, viewModel.uiState.value.llmModels.size)
        assertEquals(1, viewModel.uiState.value.embeddingModels.size)
        assertEquals("openai/gpt-4o", viewModel.uiState.value.llmModels.first().name)
    }

    @Test
    fun `createAgent forwards tool ids and include base tools`() = runTest {
        val paramsSlot = slot<AgentCreateParams>()
        coEvery { agentRepository.createAgent(capture(paramsSlot)) } returns Agent(id = "a1", name = "Agent")

        var createdId: String? = null
        viewModel.createAgent(
            AgentCreateParams(
                name = "Agent",
                model = "openai/gpt-4o",
                embedding = "openai/text-embedding-3-small",
                toolIds = listOf("t1", "t2"),
                includeBaseTools = true,
            )
        ) { createdId = it }

        assertEquals(listOf("t1", "t2"), paramsSlot.captured.toolIds)
        assertTrue(paramsSlot.captured.includeBaseTools == true)
        assertEquals("a1", createdId)
        coVerify(exactly = 1) { agentRepository.createAgent(any()) }
    }

    @Test
    fun `importAgent forwards safety flags and returns imported ids`() = runTest {
        coEvery {
            agentRepository.importAgent(
                fileName = "agent.json",
                fileBytes = any(),
                overrideName = "Cloned Agent",
                overrideExistingTools = false,
                projectId = null,
                stripMessages = true,
            )
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
            agentRepository.importAgent(
                fileName = "agent.json",
                fileBytes = any(),
                overrideName = "Cloned Agent",
                overrideExistingTools = false,
                projectId = null,
                stripMessages = true,
            )
        }
    }

    @Test
    fun `getAllTags returns sorted distinct tags from all agents`() = runTest {
        every { agentRepository.agents } returns MutableStateFlow(
            listOf(
                Agent(id = "a1", name = "Agent1", tags = listOf("beta", "alpha")),
                Agent(id = "a2", name = "Agent2", tags = listOf("alpha", "gamma")),
                Agent(id = "a3", name = "Agent3", tags = emptyList()),
            )
        )
        viewModel = AgentListViewModel(agentRepository, settingsRepository, toolRepository, modelRepository)

        val tags = viewModel.getAllTags()
        assertEquals(listOf("alpha", "beta", "gamma"), tags)
    }

    @Test
    fun `getAllTags returns empty list when no agents have tags`() = runTest {
        every { agentRepository.agents } returns MutableStateFlow(
            listOf(
                Agent(id = "a1", name = "Agent1"),
                Agent(id = "a2", name = "Agent2"),
            )
        )
        viewModel = AgentListViewModel(agentRepository, settingsRepository, toolRepository, modelRepository)

        assertTrue(viewModel.getAllTags().isEmpty())
    }

    @Test
    fun `toggleTag adds tag to selectedTags`() = runTest {
        viewModel.toggleTag("alpha")
        assertEquals(setOf("alpha"), viewModel.uiState.value.selectedTags)
    }

    @Test
    fun `toggleTag removes tag when already selected`() = runTest {
        viewModel.toggleTag("alpha")
        viewModel.toggleTag("alpha")
        assertTrue(viewModel.uiState.value.selectedTags.isEmpty())
    }

    @Test
    fun `toggleTag supports multi-select`() = runTest {
        viewModel.toggleTag("alpha")
        viewModel.toggleTag("beta")
        assertEquals(setOf("alpha", "beta"), viewModel.uiState.value.selectedTags)
    }

    @Test
    fun `clearTags resets selectedTags to empty`() = runTest {
        viewModel.toggleTag("alpha")
        viewModel.toggleTag("beta")
        viewModel.clearTags()
        assertTrue(viewModel.uiState.value.selectedTags.isEmpty())
    }

    @Test
    fun `getFilteredAgents filters by selected tags with AND logic`() = runTest {
        every { agentRepository.agents } returns MutableStateFlow(
            listOf(
                Agent(id = "a1", name = "Agent1", tags = listOf("alpha", "beta")),
                Agent(id = "a2", name = "Agent2", tags = listOf("alpha")),
                Agent(id = "a3", name = "Agent3", tags = listOf("beta", "gamma")),
            )
        )
        viewModel = AgentListViewModel(agentRepository, settingsRepository, toolRepository, modelRepository)

        // No filter — all agents
        assertEquals(3, viewModel.getFilteredAgents().size)

        // Single tag filter
        viewModel.toggleTag("alpha")
        val alphaFiltered = viewModel.getFilteredAgents()
        assertEquals(2, alphaFiltered.size)
        assertTrue(alphaFiltered.any { it.id == "a1" })
        assertTrue(alphaFiltered.any { it.id == "a2" })

        // AND logic: alpha + beta — only a1 has both
        viewModel.toggleTag("beta")
        val andFiltered = viewModel.getFilteredAgents()
        assertEquals(1, andFiltered.size)
        assertEquals("a1", andFiltered.first().id)
    }

    @Test
    fun `getFilteredAgents combines tag filter with search query`() = runTest {
        every { agentRepository.agents } returns MutableStateFlow(
            listOf(
                Agent(id = "a1", name = "ChatBot", tags = listOf("production")),
                Agent(id = "a2", name = "HelperBot", tags = listOf("production")),
                Agent(id = "a3", name = "TestBot", tags = listOf("staging")),
            )
        )
        viewModel = AgentListViewModel(agentRepository, settingsRepository, toolRepository, modelRepository)

        viewModel.toggleTag("production")
        viewModel.updateSearchQuery("Chat")

        val results = viewModel.getFilteredAgents()
        assertEquals(1, results.size)
        assertEquals("a1", results.first().id)
    }

    @Test
    fun `getFilteredAgents returns all when no tags selected and no search`() = runTest {
        every { agentRepository.agents } returns MutableStateFlow(
            listOf(
                Agent(id = "a1", name = "Agent1", tags = listOf("alpha")),
                Agent(id = "a2", name = "Agent2"),
            )
        )
        viewModel = AgentListViewModel(agentRepository, settingsRepository, toolRepository, modelRepository)

        assertEquals(2, viewModel.getFilteredAgents().size)
    }

    @Test
    fun `deleteAgent removes deleted agent from ui state immediately`() = runTest {
        val agentsFlow = MutableStateFlow(
            listOf(
                Agent(id = "a1", name = "Agent1"),
                Agent(id = "a2", name = "Agent2"),
            )
        )
        val favFlow = MutableStateFlow<String?>("a1")
        every { agentRepository.agents } returns agentsFlow
        every { settingsRepository.favoriteAgentId } returns favFlow
        every { settingsRepository.setFavoriteAgentId(any()) } answers { favFlow.value = firstArg() }
        coEvery { agentRepository.deleteAgent("a1") } answers {
            agentsFlow.value = agentsFlow.value.filterNot { it.id == "a1" }
        }
        viewModel = AgentListViewModel(agentRepository, settingsRepository, toolRepository, modelRepository)

        viewModel.deleteAgent("a1")

        assertEquals(listOf("a2"), viewModel.uiState.value.agents.map { it.id })
        assertEquals(null, viewModel.uiState.value.favoriteAgentId)
        coVerify(exactly = 1) { agentRepository.deleteAgent("a1") }
        io.mockk.verify(exactly = 1) { settingsRepository.setFavoriteAgentId(null) }
    }
}
