package com.letta.mobile.data.session

import com.letta.mobile.data.repository.api.IToolRepository
import com.letta.mobile.data.repository.api.IMcpServerRepository
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.AgentEntity
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.ConversationEntity
import com.letta.mobile.data.local.ConversationRefreshEntity
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.McpServerId
import com.letta.mobile.data.repository.api.McpServerRemoteSource
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.FakeModelApi
import com.letta.mobile.testutil.FakeProjectApi
import com.letta.mobile.testutil.FakeProviderApi
import com.letta.mobile.testutil.FakeRunApi
import com.letta.mobile.testutil.FakeScheduleApi
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.FakeToolApi
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionScopedToolMcpRepositoryTest {

    @Test
    fun `tool repository proxy switches caches to rebuilt graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeToolApi = FakeToolApi().apply {
            tools = mutableListOf(sampleTool("tool-a"))
        }
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = createTestDefaultSessionRepositoryGraphFactory {
                this.toolApi = fakeToolApi
            },
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val toolProxy = sessionManager.sessionScoped(IToolRepository::class) { it.toolRepository }
        val agentTools = toolProxy.getAgentTools("agent-1")

        toolProxy.refreshTools()
        toolProxy.attachTool("agent-1", "tool-a")
        advanceUntilIdle()
        assertEquals(listOf("tool-a"), toolProxy.getTools().value.map { it.id.value })
        assertEquals(listOf("tool-a"), agentTools.first().map { it.id.value })

        fakeToolApi.tools = mutableListOf(sampleTool("tool-b"))
        settingsRepository.activeConfigState.value = sessionTestConfig("backend-b")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), toolProxy.getTools().value.map { it.id.value })
        assertEquals(emptyList<String>(), agentTools.first().map { it.id.value })

        val rebuiltAgentTools = toolProxy.getAgentTools("agent-1")
        toolProxy.refreshTools()
        toolProxy.attachTool("agent-1", "tool-b")
        advanceUntilIdle()

        assertEquals(listOf("tool-b"), toolProxy.getTools().value.map { it.id.value })
        assertEquals(listOf("tool-b"), rebuiltAgentTools.first().map { it.id.value })
    }

    @Test
    fun `mcp server repository proxy switches the server list to the rebuilt graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeMcpServerApi = RecordingMcpServerSource().apply {
            servers = mutableListOf(sampleMcpServer("server-a"))
        }
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = createTestDefaultSessionRepositoryGraphFactory {
                this.mcpServerApi = fakeMcpServerApi
            },
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val mcpProxy = sessionManager.sessionScoped(IMcpServerRepository::class) { it.mcpServerRepository }

        mcpProxy.refreshServers()
        advanceUntilIdle()
        assertEquals(listOf(McpServerId("server-a")), mcpProxy.servers.value.map { it.id })

        fakeMcpServerApi.servers = mutableListOf(sampleMcpServer("server-b"))
        settingsRepository.activeConfigState.value = sessionTestConfig("backend-b")
        advanceUntilIdle()

        assertEquals(emptyList<McpServerId>(), mcpProxy.servers.value.map { it.id })

        mcpProxy.refreshServers()
        advanceUntilIdle()

        assertEquals(listOf(McpServerId("server-b")), mcpProxy.servers.value.map { it.id })
    }

    private fun sampleTool(id: String) = Tool(
        id = ToolId(id),
        name = id,
    )

    private fun sampleMcpServer(id: String) = McpServer(
        id = McpServerId(id),
        serverName = id,
    )

    /** The MCP catalog the fake backend answers, so the graph caches one row per generation. */
    private class RecordingMcpServerSource : McpServerRemoteSource {
        var servers: List<McpServer> = emptyList()

        override suspend fun listMcpServers(limit: Int?, offset: Int?): List<McpServer> = servers

        override suspend fun listMcpServerTools(serverId: String): List<Tool> = emptyList()
    }
}
