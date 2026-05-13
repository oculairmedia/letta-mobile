package com.letta.mobile.ui.screens.tools

import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.repository.McpServerRepository
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.testutil.FakeToolApi
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class AllToolsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeRepository: FakeToolRepository
    private lateinit var mockMcpServerRepository: McpServerRepository
    private lateinit var viewModel: AllToolsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeToolRepository()
        mockMcpServerRepository = mockk(relaxed = true)
        coEvery { mockMcpServerRepository.fetchAllMcpTools() } returns emptyList()
        viewModel = AllToolsViewModel(fakeRepository, mockMcpServerRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `updateSearchQuery filters tools by name and description`() = runTest {
        fakeRepository.setTools(
            listOf(
                Tool(id = ToolId("t1"), name = "weather_lookup", description = "Forecast helper"),
                Tool(id = ToolId("t2"), name = "calendar_sync", description = "Calendar helper"),
            )
        )
        viewModel.loadTools()
        awaitSuccessState()

        viewModel.updateSearchQuery("forecast")

        val filtered = viewModel.getFilteredTools()
        assertEquals(1, filtered.size)
        assertEquals("weather_lookup", filtered.first().name)
    }

    @Test
    fun `getFilteredTools returns all tools when query blank`() = runTest {
        fakeRepository.setTools(
            listOf(
                Tool(id = ToolId("t1"), name = "weather_lookup"),
                Tool(id = ToolId("t2"), name = "calendar_sync"),
            )
        )
        viewModel.loadTools()
        awaitSuccessState()

        val filtered = viewModel.getFilteredTools()
        assertEquals(2, filtered.size)
    }

    @Test
    fun `loadTools publishes regular tools before slow mcp tools finish`() = runTest {
        fakeRepository.setTools(
            listOf(
                Tool(id = ToolId("t1"), name = "weather_lookup"),
                Tool(id = ToolId("t2"), name = "calendar_sync"),
            )
        )
        val mcpGate = CompletableDeferred<List<Tool>>()
        coEvery { mockMcpServerRepository.fetchAllMcpTools() } coAnswers { mcpGate.await() }

        viewModel = AllToolsViewModel(fakeRepository, mockMcpServerRepository)

        val initial = awaitSuccessState()
        assertEquals(listOf("t1", "t2"), initial.tools.map { it.id.value })
        assertEquals(true, initial.isLoadingMcpTools)

        mcpGate.complete(listOf(Tool(id = ToolId("m1"), name = "mcp_tool")))
        advanceUntilIdle()

        val enriched = awaitSuccessState()
        assertEquals(listOf("m1", "t1", "t2"), enriched.tools.map { it.id.value })
        assertEquals(false, enriched.isLoadingMcpTools)
    }

    @Test
    fun `loadMoreTools keeps paged tools when mcp tools finish later`() = runTest {
        fakeRepository.setTools((1..51).map { index -> Tool(id = ToolId("t$index"), name = "tool_$index") })
        val mcpGate = CompletableDeferred<List<Tool>>()
        coEvery { mockMcpServerRepository.fetchAllMcpTools() } coAnswers { mcpGate.await() }

        viewModel = AllToolsViewModel(fakeRepository, mockMcpServerRepository)
        val initial = awaitSuccessState()
        assertEquals(50, initial.tools.size)
        assertEquals(true, initial.hasMorePages)

        viewModel.loadMoreTools()
        advanceUntilIdle()

        val paged = awaitSuccessState()
        assertEquals(51, paged.tools.size)
        assertEquals("t51", paged.tools.last().id.value)
        assertEquals(true, paged.isLoadingMcpTools)

        mcpGate.complete(listOf(Tool(id = ToolId("m1"), name = "mcp_tool")))
        advanceUntilIdle()

        val enriched = awaitSuccessState()
        assertEquals(52, enriched.tools.size)
        assertEquals("m1", enriched.tools.first().id.value)
        assertEquals("t51", enriched.tools.last().id.value)
        assertEquals(false, enriched.isLoadingMcpTools)
    }

    private class FakeToolRepository : ToolRepository(FakeToolApi()) {
        private var tools: List<Tool> = emptyList()

        fun setTools(tools: List<Tool>) {
            this.tools = tools
        }

        override suspend fun fetchToolsPage(limit: Int, offset: Int): List<Tool> =
            tools.drop(offset).take(limit)
    }

    private suspend fun awaitSuccessState(): AllToolsUiState {
        return viewModel.uiState.first { it is com.letta.mobile.ui.common.UiState.Success }
            .let { (it as com.letta.mobile.ui.common.UiState.Success).data }
    }
}
