package com.letta.mobile.ui.screens.tools

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.testutil.FakeToolApi
import com.letta.mobile.testutil.TestData
import com.letta.mobile.ui.common.UiState
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

@OptIn(ExperimentalCoroutinesApi::class)
class ToolDetailViewModelTest {

    private lateinit var fakeToolApi: FakeToolApi
    private lateinit var toolRepository: ToolRepository
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeToolApi = FakeToolApi()
        toolRepository = ToolRepository(fakeToolApi)
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `loadTool sets Success with tool data`() = runTest {
        val tool = TestData.tool(id = "t1", name = "my_tool", description = "Does things")
        fakeToolApi.tools.add(tool.copy(toolType = "custom", sourceCode = "def my_tool():\n    return 'ok'"))
        val savedState = SavedStateHandle(mapOf("toolId" to "t1"))
        val viewModel = ToolDetailViewModel(savedState, fakeToolApi, toolRepository)
        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertEquals("my_tool", state.data.name)
            assertEquals("Does things", state.data.description)
        }
    }

    @Test
    fun `loadTool sets Error on failure`() = runTest {
        fakeToolApi.shouldFail = true
        val savedState = SavedStateHandle(mapOf("toolId" to "t1"))
        val viewModel = ToolDetailViewModel(savedState, fakeToolApi, toolRepository)
        viewModel.uiState.test { assertTrue(awaitItem() is UiState.Error) }
    }

    @Test
    fun `loadTool sets Error for missing tool`() = runTest {
        val savedState = SavedStateHandle(mapOf("toolId" to "nonexistent"))
        val viewModel = ToolDetailViewModel(savedState, fakeToolApi, toolRepository)
        viewModel.uiState.test { assertTrue(awaitItem() is UiState.Error) }
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
        val viewModel = ToolDetailViewModel(savedState, fakeToolApi, toolRepository)

        viewModel.updateTool(
            name = "my_tool_v2",
            description = "Updated",
            sourceCode = "def my_tool_v2():\n    return 'updated'",
            tags = listOf("admin")
        )

        viewModel.uiState.test {
            awaitItem()
            val state = awaitItem() as UiState.Success
            assertEquals("my_tool_v2", state.data.name)
            assertEquals("Updated", state.data.description)
        }
    }

    @Test
    fun `deleteTool sets success state`() = runTest {
        fakeToolApi.tools.add(
            TestData.tool(id = "t1", name = "my_tool").copy(toolType = "custom", sourceCode = "def my_tool():\n    return 'ok'")
        )
        val savedState = SavedStateHandle(mapOf("toolId" to "t1"))
        val viewModel = ToolDetailViewModel(savedState, fakeToolApi, toolRepository)

        viewModel.deleteTool()

        viewModel.deleteState.test {
            awaitItem()
            assertTrue(awaitItem() is UiState.Success)
        }
    }

    @Test
    fun `deleteTool sets error on failure`() = runTest {
        fakeToolApi.shouldFail = true
        val savedState = SavedStateHandle(mapOf("toolId" to "t1"))
        val viewModel = ToolDetailViewModel(savedState, fakeToolApi, toolRepository)

        viewModel.deleteTool()

        viewModel.deleteState.test {
            awaitItem()
            assertTrue(awaitItem() is UiState.Error)
        }
    }
}
