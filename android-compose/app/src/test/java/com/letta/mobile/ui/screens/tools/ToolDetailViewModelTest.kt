package com.letta.mobile.ui.screens.tools

import com.letta.mobile.data.model.AgentId
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.FakeToolApi
import com.letta.mobile.testutil.TestData
import io.mockk.mockk
import com.letta.mobile.ui.common.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
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
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class ToolDetailViewModelTest {

    private lateinit var fakeToolApi: FakeToolApi
    private lateinit var toolRepository: ToolRepository
    private lateinit var agentRepository: AgentRepository
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeToolApi = FakeToolApi()
        toolRepository = ToolRepository(fakeToolApi)
        agentRepository = FakeAgentRepository()
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `loadTool sets Success with tool data`() = runTest {
        val tool = TestData.tool(id = "t1", name = "my_tool", description = "Does things")
        fakeToolApi.tools.add(tool.copy(toolType = "custom", sourceCode = "def my_tool():\n    return 'ok'"))
        val savedState = SavedStateHandle(mapOf("toolId" to "t1"))
        val viewModel = ToolDetailViewModel(savedState, fakeToolApi, toolRepository, agentRepository)
        val state = awaitToolSuccess(viewModel)
        assertEquals("my_tool", state.name)
        assertEquals("Does things", state.description)
    }

    @Test
    fun `loadTool sets Error on failure`() = runTest {
        fakeToolApi.shouldFail = true
        val savedState = SavedStateHandle(mapOf("toolId" to "t1"))
        val viewModel = ToolDetailViewModel(savedState, fakeToolApi, toolRepository, agentRepository)
        assertTrue(viewModel.uiState.first { it is UiState.Error } is UiState.Error)
    }

    @Test
    fun `loadTool sets Error for missing tool`() = runTest {
        val savedState = SavedStateHandle(mapOf("toolId" to "nonexistent"))
        val viewModel = ToolDetailViewModel(savedState, fakeToolApi, toolRepository, agentRepository)
        assertTrue(viewModel.uiState.first { it is UiState.Error } is UiState.Error)
    }

    @Test
    fun `updateTool updates current tool`() = runTest {
        fakeToolApi.tools.add(
            TestData.tool(id = "t1", name = "my_tool", description = "Does things").copy(
                toolType = "custom",
                sourceCode = "def my_tool():\n    return 'ok'",
            )
        )
        val savedState = SavedStateHandle(mapOf("toolId" to "t1"))
        val viewModel = ToolDetailViewModel(savedState, fakeToolApi, toolRepository, agentRepository)
        awaitToolSuccess(viewModel)

        viewModel.updateTool(
            description = "Updated",
            sourceCode = "def my_tool_v2():\n    return 'updated'",
            tags = listOf("admin")
        )

        val state = awaitToolSuccess(viewModel)
        assertEquals("my_tool_v2", state.name)
        assertEquals("Updated", state.description)
        assertTrue(fakeToolApi.calls.contains("generateJsonSchema:python"))
    }

    @Test
    fun `deleteTool sets success state`() = runTest {
        fakeToolApi.tools.add(
            TestData.tool(id = "t1", name = "my_tool").copy(toolType = "custom", sourceCode = "def my_tool():\n    return 'ok'")
        )
        val savedState = SavedStateHandle(mapOf("toolId" to "t1"))
        val viewModel = ToolDetailViewModel(savedState, fakeToolApi, toolRepository, agentRepository)

        viewModel.deleteTool()

        assertTrue(awaitDeleteState(viewModel) is UiState.Success)
    }

    @Test
    fun `deleteTool sets error on failure`() = runTest {
        fakeToolApi.shouldFail = true
        val savedState = SavedStateHandle(mapOf("toolId" to "t1"))
        val viewModel = ToolDetailViewModel(savedState, fakeToolApi, toolRepository, agentRepository)

        viewModel.deleteTool()

        assertTrue(awaitDeleteState(viewModel) is UiState.Error)
    }

    @Test
    fun `attachToAgent delegates to repository`() = runTest {
        fakeToolApi.tools.add(TestData.tool(id = "t1", name = "my_tool"))
        val savedState = SavedStateHandle(mapOf("toolId" to "t1"))
        val toolRepo = FakeToolRepository(fakeToolApi)
        val viewModel = ToolDetailViewModel(savedState, fakeToolApi, toolRepo, agentRepository)

        viewModel.attachToAgent("a1")

        assertEquals(listOf("a1"), toolRepo.attachedAgents)
    }

    @Test
    fun `detachFromAgent delegates to repository`() = runTest {
        fakeToolApi.tools.add(TestData.tool(id = "t1", name = "my_tool"))
        val savedState = SavedStateHandle(mapOf("toolId" to "t1"))
        val toolRepo = FakeToolRepository(fakeToolApi)
        val viewModel = ToolDetailViewModel(savedState, fakeToolApi, toolRepo, agentRepository)

        viewModel.detachFromAgent("a1")

        assertEquals(listOf("a1"), toolRepo.detachedAgents)
    }

    private class FakeToolRepository(api: FakeToolApi) : ToolRepository(api) {
        val attachedAgents = mutableListOf<String>()
        val detachedAgents = mutableListOf<String>()

        override suspend fun attachTool(agentId: String, toolId: String) {
            attachedAgents.add(agentId)
        }

        override suspend fun detachTool(agentId: String, toolId: String) {
            detachedAgents.add(agentId)
        }
    }

    private class FakeAgentRepository : AgentRepository(FakeAgentApi(), mockk(relaxed = true)) {
        private val agentsFlow = kotlinx.coroutines.flow.MutableStateFlow(
            listOf(
                Agent(id = AgentId("a1"), name = "Agent One", tools = listOf(TestData.tool(id = "t1", name = "my_tool"))),
                Agent(id = AgentId("a2"), name = "Agent Two", tools = emptyList()),
            )
        )

        override val agents = agentsFlow
        override suspend fun refreshAgents() {}
        override fun getAgent(id: String) = kotlinx.coroutines.flow.flow { emit(agentsFlow.value.first { it.id.value == id }) }
        override suspend fun createAgent(params: com.letta.mobile.data.model.AgentCreateParams) = agentsFlow.value.first()
        override suspend fun updateAgent(id: String, params: com.letta.mobile.data.model.AgentUpdateParams) = agentsFlow.value.first { it.id.value == id }
        override suspend fun deleteAgent(id: String) {}
    }

    private suspend fun awaitToolSuccess(viewModel: ToolDetailViewModel): Tool {
        return viewModel.uiState.first { it is UiState.Success }.let { (it as UiState.Success).data }
    }

    private suspend fun awaitDeleteState(viewModel: ToolDetailViewModel): UiState<Unit> {
        return viewModel.deleteState.filterNotNull().first { it !is UiState.Loading }
    }
}
