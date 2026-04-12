package com.letta.mobile.ui.screens.tools

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.testutil.FakeToolApi
import com.letta.mobile.testutil.TestData
import com.letta.mobile.ui.common.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolsViewModelTest {

    private lateinit var fakeRepo: FakeToolRepository
    private lateinit var viewModel: ToolsViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeToolRepository()
        val savedState = SavedStateHandle(mapOf("agentId" to "a1"))
        viewModel = ToolsViewModel(savedState, fakeRepo)
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `loadTools sets Success`() = runTest {
        fakeRepo.setTools(listOf(TestData.tool(id = "1"), TestData.tool(id = "2")))
        viewModel.loadTools()
        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
        }
    }

    @Test
    fun `loadTools sets Error on failure`() = runTest {
        fakeRepo.shouldFail = true
        viewModel.loadTools()
        viewModel.uiState.test { assertTrue(awaitItem() is UiState.Error) }
    }

    @Test
    fun `addTool calls repo`() = runTest {
        fakeRepo.setTools(emptyList())
        viewModel.loadTools()
        viewModel.addTool("t1")
        assertTrue(fakeRepo.attachCalls.contains("a1:t1"))
    }

    @Test
    fun `removeTool calls repo`() = runTest {
        fakeRepo.setTools(listOf(TestData.tool(id = "t1")))
        viewModel.loadTools()
        viewModel.removeTool("t1")
        assertTrue(fakeRepo.detachCalls.contains("a1:t1"))
    }

    private class FakeToolRepository : ToolRepository(FakeToolApi()) {
        private val _tools = MutableStateFlow<List<Tool>>(emptyList())
        var shouldFail = false
        val attachCalls = mutableListOf<String>()
        val detachCalls = mutableListOf<String>()

        fun setTools(list: List<Tool>) { _tools.value = list }
        override fun getTools(): StateFlow<List<Tool>> = _tools.asStateFlow()
        override suspend fun refreshTools() { if (shouldFail) throw Exception("Failed") }
        override suspend fun attachTool(agentId: String, toolId: String) { attachCalls.add("$agentId:$toolId") }
        override suspend fun detachTool(agentId: String, toolId: String) { detachCalls.add("$agentId:$toolId") }
        override suspend fun upsertTool(params: ToolCreateParams): Tool = TestData.tool()
    }
}
