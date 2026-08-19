package com.letta.mobile.data.session

import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.AgentEntity
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.ConversationEntity
import com.letta.mobile.data.local.ConversationRefreshEntity
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.McpServerId
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.FakeArchiveApi
import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.FakeFolderApi
import com.letta.mobile.testutil.FakeGroupApi
import com.letta.mobile.testutil.FakeIdentityApi
import com.letta.mobile.testutil.FakeJobApi
import com.letta.mobile.testutil.FakeMcpServerApi
import com.letta.mobile.testutil.FakeModelApi
import com.letta.mobile.testutil.FakePassageApi
import com.letta.mobile.testutil.FakeProjectApi
import com.letta.mobile.testutil.FakeProjectWorkApi
import com.letta.mobile.testutil.FakeProviderApi
import com.letta.mobile.testutil.FakeRunApi
import com.letta.mobile.testutil.FakeScheduleApi
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.FakeStepApi
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
        val toolProxy = SessionScopedToolRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
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
    fun `mcp server repository proxy switches caches to rebuilt graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeMcpServerApi = FakeMcpServerApi().apply {
            servers = mutableListOf(sampleMcpServer("server-a"))
            serverTools["server-a"] = listOf(sampleTool("mcp-tool-a"))
        }
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = sessionTestConfig("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = createTestDefaultSessionRepositoryGraphFactory {
                this.mcpServerApi = fakeMcpServerApi
            },
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val mcpProxy = SessionScopedMcpServerRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val serverTools = mcpProxy.getServerTools(McpServerId("server-a"))

        mcpProxy.refreshServers()
        mcpProxy.refreshServerTools(McpServerId("server-a"))
        advanceUntilIdle()
        assertEquals(listOf(McpServerId("server-a")), mcpProxy.servers.value.map { it.id })
        assertEquals(listOf("mcp-tool-a"), serverTools.first().map { it.id.value })

        fakeMcpServerApi.servers = mutableListOf(sampleMcpServer("server-b"))
        fakeMcpServerApi.serverTools = mutableMapOf("server-a" to listOf(sampleTool("mcp-tool-b")))
        settingsRepository.activeConfigState.value = sessionTestConfig("backend-b")
        advanceUntilIdle()

        assertEquals(emptyList<McpServerId>(), mcpProxy.servers.value.map { it.id })
        assertEquals(emptyList<String>(), serverTools.first().map { it.id.value })

        val rebuiltServerTools = mcpProxy.getServerTools(McpServerId("server-a"))
        mcpProxy.refreshServers()
        mcpProxy.refreshServerTools(McpServerId("server-a"))
        advanceUntilIdle()

        assertEquals(listOf(McpServerId("server-b")), mcpProxy.servers.value.map { it.id })
        assertEquals(listOf("mcp-tool-b"), rebuiltServerTools.first().map { it.id.value })
    }

    private fun sampleTool(id: String) = Tool(
        id = ToolId(id),
        name = id,
    )

    private fun sampleMcpServer(id: String) = McpServer(
        id = McpServerId(id),
        serverName = id,
    )
}
