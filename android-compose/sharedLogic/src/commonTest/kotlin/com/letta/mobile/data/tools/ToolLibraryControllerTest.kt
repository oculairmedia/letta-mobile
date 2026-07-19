package com.letta.mobile.data.tools

import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.McpServerCreateParams
import com.letta.mobile.data.model.McpServerId
import com.letta.mobile.data.model.McpServerResyncResult
import com.letta.mobile.data.model.McpServerUpdateParams
import com.letta.mobile.data.model.McpToolExecuteParams
import com.letta.mobile.data.model.McpToolExecutionResult
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.model.ToolUpdateParams
import com.letta.mobile.data.repository.api.IMcpServerRepository
import com.letta.mobile.data.repository.api.IToolRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ToolLibraryControllerTest {
    @Test
    fun filtersToolsBySearchAndSelectedTagsInCommonCode() {
        val tools = listOf(
            tool("t1", "weather_lookup", description = "Forecast helper", tags = listOf("weather", "remote")),
            tool("t2", "calendar_sync", description = "Calendar helper", tags = listOf("calendar")),
            tool("t3", "weather_cache", description = "Local store", tags = listOf("weather", "local")),
        )

        assertEquals(
            listOf("weather_lookup"),
            ToolLibraryFilter.filter(
                tools = tools,
                searchQuery = "forecast",
                selectedTags = setOf("weather"),
            ).map { it.name },
        )
        assertEquals(
            listOf("weather_lookup", "weather_cache"),
            ToolLibraryState(
                tools = tools,
                selectedTags = setOf("weather"),
            ).filteredTools.map { it.name },
        )
    }

    @Test
    fun loadToolsPublishesRegularToolsBeforeMcpToolsFinish() = runTest {
        val toolRepository = FakeToolRepository(
            tools = listOf(
                tool("t1", "weather_lookup"),
                tool("t2", "calendar_sync"),
            ),
        )
        val mcpGate = CompletableDeferred<List<Tool>>()
        val controller = ToolLibraryController(
            toolRepository = toolRepository,
            mcpServerRepository = FakeMcpServerRepository { mcpGate.await() },
            scope = this,
        )

        controller.start()
        advanceUntilIdle()

        assertEquals(listOf("t1", "t2"), controller.state.value.tools.map { it.id.value })
        assertEquals(true, controller.state.value.isLoadingMcpTools)

        mcpGate.complete(listOf(tool("m1", "mcp_tool")))
        advanceUntilIdle()

        assertEquals(listOf("m1", "t1", "t2"), controller.state.value.tools.map { it.id.value })
        assertEquals(setOf("m1"), controller.state.value.mcpToolIds)
        assertEquals(false, controller.state.value.isLoadingMcpTools)
    }

    @Test
    fun loadMoreToolsKeepsPagedToolsWhenMcpToolsFinishLater() = runTest {
        val toolRepository = FakeToolRepository(
            tools = (1..51).map { index -> tool("t$index", "tool_$index") },
        )
        val mcpGate = CompletableDeferred<List<Tool>>()
        val controller = ToolLibraryController(
            toolRepository = toolRepository,
            mcpServerRepository = FakeMcpServerRepository { mcpGate.await() },
            scope = this,
        )

        controller.start()
        advanceUntilIdle()

        assertEquals(50, controller.state.value.tools.size)
        assertEquals(true, controller.state.value.hasMorePages)

        controller.loadMoreTools()
        advanceUntilIdle()

        assertEquals(51, controller.state.value.tools.size)
        assertEquals("t51", controller.state.value.tools.last().id.value)
        assertEquals(true, controller.state.value.isLoadingMcpTools)

        mcpGate.complete(listOf(tool("m1", "mcp_tool")))
        advanceUntilIdle()

        assertEquals(52, controller.state.value.tools.size)
        assertEquals("m1", controller.state.value.tools.first().id.value)
        assertEquals("t51", controller.state.value.tools.last().id.value)
        assertEquals(false, controller.state.value.isLoadingMcpTools)
    }

    private class FakeToolRepository(
        private val tools: List<Tool>,
    ) : IToolRepository {
        private val toolsFlow = MutableStateFlow(tools)

        override fun getTools(): StateFlow<List<Tool>> = toolsFlow

        override fun getAgentTools(agentId: AgentId): Flow<List<Tool>> = emptyFlow()

        override suspend fun countTools(): Int = tools.size

        override suspend fun refreshTools() {
            toolsFlow.value = tools
        }

        override suspend fun refreshToolsIfStale(maxAgeMs: Long): Boolean = false

        override suspend fun fetchToolsPage(limit: Int, offset: Int): List<Tool> =
            tools.drop(offset).take(limit)

        override suspend fun attachTool(agentId: AgentId, toolId: ToolId) = Unit

        override suspend fun detachTool(agentId: AgentId, toolId: ToolId) = Unit

        override suspend fun upsertTool(params: ToolCreateParams): Tool = error("unused")

        override suspend fun updateTool(toolId: ToolId, params: ToolUpdateParams): Tool = error("unused")

        override suspend fun deleteTool(toolId: ToolId) = Unit
    }

    private class FakeMcpServerRepository(
        private val fetchTools: suspend () -> List<Tool> = { emptyList() },
    ) : IMcpServerRepository {
        override val servers: StateFlow<List<McpServer>> = MutableStateFlow(emptyList())

        override fun getServers(): Flow<List<McpServer>> = flowOf(emptyList())

        override fun getServerTools(serverId: McpServerId): Flow<List<Tool>> = flowOf(emptyList())

        override suspend fun refreshServers() = Unit

        override suspend fun refreshServerTools(serverId: McpServerId) = Unit

        override suspend fun resyncServerTools(serverId: McpServerId): McpServerResyncResult = error("unused")

        override suspend fun runServerTool(
            serverId: McpServerId,
            toolId: ToolId,
            params: McpToolExecuteParams,
        ): McpToolExecutionResult = error("unused")

        override suspend fun fetchAllMcpTools(): List<Tool> = fetchTools()

        override suspend fun createServer(params: McpServerCreateParams): McpServer = error("unused")

        override suspend fun updateServer(id: McpServerId, params: McpServerUpdateParams): McpServer =
            error("unused")

        override suspend fun deleteServer(id: McpServerId) = Unit
    }

    private fun tool(
        id: String,
        name: String,
        description: String? = null,
        tags: List<String> = emptyList(),
    ): Tool =
        Tool(
            id = ToolId(id),
            name = name,
            description = description,
            sourceType = "python",
            tags = tags,
        )
}
