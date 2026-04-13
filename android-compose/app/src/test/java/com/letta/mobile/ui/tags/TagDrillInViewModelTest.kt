package com.letta.mobile.ui.tags

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.StepListParams
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.StepRepository
import com.letta.mobile.data.repository.ToolRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
class TagDrillInViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var agentRepository: AgentRepository
    private lateinit var toolRepository: ToolRepository
    private lateinit var stepRepository: StepRepository
    private lateinit var viewModel: TagDrillInViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        agentRepository = mockk(relaxed = true)
        toolRepository = mockk(relaxed = true)
        stepRepository = mockk(relaxed = true)

        every { agentRepository.agents } returns MutableStateFlow(
            listOf(Agent(id = "agent-1", name = "Starter Agent", tags = listOf("starter", "general")))
        )
        every { toolRepository.getTools() } returns MutableStateFlow(
            listOf(Tool(id = "tool-1", name = "Starter Tool", tags = listOf("starter", "utility")))
        )
        coEvery { agentRepository.refreshAgentsIfStale(any()) } returns false
        coEvery { toolRepository.refreshToolsIfStale(any()) } returns false
        coEvery { stepRepository.listSteps(any()) } returns listOf(
            Step(id = "step-1", status = "running", tags = listOf("starter"), runId = "run-1")
        )

        viewModel = TagDrillInViewModel(agentRepository, toolRepository, stepRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `showTag aggregates matching entities and excludes source item`() = runTest {
        viewModel.showTag(
            tag = "starter",
            source = TagDrillInSource(TagDrillInEntityType.TEMPLATE, "default"),
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("starter", state.activeTag)
        assertFalse(state.isLoading)
        assertTrue(state.items.any { it.entityType == TagDrillInEntityType.AGENT && it.id == "agent-1" })
        assertTrue(state.items.any { it.entityType == TagDrillInEntityType.TOOL && it.id == "tool-1" })
        assertTrue(state.items.any { it.entityType == TagDrillInEntityType.STEP && it.id == "step-1" })
        assertTrue(state.items.any { it.entityType == TagDrillInEntityType.TEMPLATE && it.id == "coder" })
        assertTrue(state.items.any { it.entityType == TagDrillInEntityType.TEMPLATE && it.id == "writer" })
        assertFalse(state.items.any { it.entityType == TagDrillInEntityType.TEMPLATE && it.id == "default" })
    }

    @Test
    fun `showTag requests filtered steps for active tag`() = runTest {
        val paramsSlot = slot<StepListParams>()
        coEvery { stepRepository.listSteps(capture(paramsSlot)) } returns emptyList()

        viewModel.showTag("coding")
        advanceUntilIdle()

        assertEquals(listOf("coding"), paramsSlot.captured.tags)
        assertEquals("desc", paramsSlot.captured.order)
    }
}
