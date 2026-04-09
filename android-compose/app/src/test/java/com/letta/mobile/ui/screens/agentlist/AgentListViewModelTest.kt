package com.letta.mobile.ui.screens.agentlist

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.repository.AgentRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
class AgentListViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var agentRepository: AgentRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var toolRepository: ToolRepository
    private lateinit var viewModel: AgentListViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        agentRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        toolRepository = mockk(relaxed = true)

        every { agentRepository.agents } returns MutableStateFlow(emptyList())
        every { settingsRepository.favoriteAgentId } returns MutableStateFlow(null)
        every { toolRepository.getTools() } returns flowOf(
            listOf(
                Tool(id = "t1", name = "tool_one"),
                Tool(id = "t2", name = "tool_two"),
            )
        )

        viewModel = AgentListViewModel(agentRepository, settingsRepository, toolRepository)
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
}
