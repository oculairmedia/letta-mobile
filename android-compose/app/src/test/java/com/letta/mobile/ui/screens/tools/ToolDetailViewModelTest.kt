package com.letta.mobile.ui.screens.tools

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.letta.mobile.data.model.Tool
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
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeToolApi = FakeToolApi()
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `loadTool sets Success with tool data`() = runTest {
        val tool = TestData.tool(id = "t1", name = "my_tool", description = "Does things")
        fakeToolApi.tools.add(tool)
        val savedState = SavedStateHandle(mapOf("toolId" to "t1"))
        val viewModel = ToolDetailViewModel(savedState, fakeToolApi)
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
        val viewModel = ToolDetailViewModel(savedState, fakeToolApi)
        viewModel.uiState.test { assertTrue(awaitItem() is UiState.Error) }
    }

    @Test
    fun `loadTool sets Error for missing tool`() = runTest {
        val savedState = SavedStateHandle(mapOf("toolId" to "nonexistent"))
        val viewModel = ToolDetailViewModel(savedState, fakeToolApi)
        viewModel.uiState.test { assertTrue(awaitItem() is UiState.Error) }
    }
}
