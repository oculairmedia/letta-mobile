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
    fun `tool and mcp repository proxies switch caches to rebuilt graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeToolApi = FakeToolApi().apply {
            tools = mutableListOf(sampleTool("tool-a"))
        }
        val fakeMcpServerApi = FakeMcpServerApi().apply {
            servers = mutableListOf(sampleMcpServer("server-a"))
            serverTools["server-a"] = listOf(sampleTool("mcp-tool-a"))
        }
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = config("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = SessionGraphFactory(
                FakeAgentApi(),
                FakeAgentDao(),
                FakeConversationApi(),
                FakeConversationDao(),
                FakeArchiveApi(),
                FakeFolderApi(),
                FakeGroupApi(),
                FakeIdentityApi(),
                fakeLettaApiClient(),
                fakeMcpServerApi,
                FakeModelApi(),
                FakePassageApi(),
                FakeProjectApi(),
                FakeProjectWorkApi(),
                FakeRunApi(),
                FakeJobApi(),
                FakeProviderApi(),
                FakeScheduleApi(),
                FakeStepApi(),
                fakeToolApi,
                appContext = mockk(relaxed = true),
            ),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val toolProxy = SessionScopedToolRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val mcpProxy = SessionScopedMcpServerRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val agentTools = toolProxy.getAgentTools("agent-1")
        val serverTools = mcpProxy.getServerTools(McpServerId("server-a"))

        toolProxy.refreshTools()
        toolProxy.attachTool("agent-1", "tool-a")
        mcpProxy.refreshServers()
        mcpProxy.refreshServerTools(McpServerId("server-a"))
        advanceUntilIdle()
        assertEquals(listOf("tool-a"), toolProxy.getTools().value.map { it.id.value })
        assertEquals(listOf("tool-a"), agentTools.first().map { it.id.value })
        assertEquals(listOf(McpServerId("server-a")), mcpProxy.servers.value.map { it.id })
        assertEquals(listOf("mcp-tool-a"), serverTools.first().map { it.id.value })

        fakeToolApi.tools = mutableListOf(sampleTool("tool-b"))
        fakeMcpServerApi.servers = mutableListOf(sampleMcpServer("server-b"))
        fakeMcpServerApi.serverTools = mutableMapOf("server-a" to listOf(sampleTool("mcp-tool-b")))
        settingsRepository.activeConfigState.value = config("backend-b")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), toolProxy.getTools().value.map { it.id.value })
        assertEquals(emptyList<String>(), agentTools.first().map { it.id.value })
        assertEquals(emptyList<McpServerId>(), mcpProxy.servers.value.map { it.id })
        assertEquals(emptyList<String>(), serverTools.first().map { it.id.value })

        val rebuiltAgentTools = toolProxy.getAgentTools("agent-1")
        val rebuiltServerTools = mcpProxy.getServerTools(McpServerId("server-a"))
        toolProxy.refreshTools()
        toolProxy.attachTool("agent-1", "tool-b")
        mcpProxy.refreshServers()
        mcpProxy.refreshServerTools(McpServerId("server-a"))
        advanceUntilIdle()

        assertEquals(listOf("tool-b"), toolProxy.getTools().value.map { it.id.value })
        assertEquals(listOf("tool-b"), rebuiltAgentTools.first().map { it.id.value })
        assertEquals(listOf(McpServerId("server-b")), mcpProxy.servers.value.map { it.id })
        assertEquals(listOf("mcp-tool-b"), rebuiltServerTools.first().map { it.id.value })
    }

    private fun fakeLettaApiClient(): LettaApiClient = mockk(relaxed = true)

    private fun config(id: String, serverUrl: String = "https://$id.example.test"): LettaConfig = LettaConfig(
        id = id,
        mode = LettaConfig.Mode.SELF_HOSTED,
        serverUrl = serverUrl,
    )

    private fun sampleTool(id: String) = Tool(
        id = ToolId(id),
        name = id,
    )

    private fun sampleMcpServer(id: String) = McpServer(
        id = McpServerId(id),
        serverName = id,
    )

    private class FakeAgentDao : AgentDao {
        private val agents = MutableStateFlow<List<AgentEntity>>(emptyList())

        override fun getAll(): Flow<List<AgentEntity>> = agents

        override suspend fun getAllOnce(): List<AgentEntity> = agents.value

        override suspend fun insertAll(agents: List<AgentEntity>) {
            this.agents.value = agents
        }

        override suspend fun upsert(agent: AgentEntity) {
            agents.value = agents.value.filterNot { it.id == agent.id } + agent
        }

        override suspend fun deleteExcept(keepIds: List<String>) {
            agents.value = agents.value.filter { it.id in keepIds }
        }

        override suspend fun deleteById(id: String) {
            agents.value = agents.value.filterNot { it.id == id }
        }

        override suspend fun deleteAll() {
            agents.value = emptyList()
        }
    }

    private class FakeConversationDao : ConversationDao {
        private val conversations = MutableStateFlow<List<ConversationEntity>>(emptyList())
        private val refreshStates = mutableMapOf<String, ConversationRefreshEntity>()

        override fun observeForAgent(agentId: String): Flow<List<ConversationEntity>> =
            conversations.map { rows -> rows.filter { it.agentId == agentId } }

        override suspend fun getForAgentOnce(agentId: String): List<ConversationEntity> =
            conversations.value.filter { it.agentId == agentId }

        override suspend fun getAllOnce(): List<ConversationEntity> = conversations.value

        override suspend fun getByIdOnce(conversationId: String): ConversationEntity? =
            conversations.value.firstOrNull { it.id == conversationId }

        override suspend fun upsert(conversation: ConversationEntity) {
            conversations.value = conversations.value.filterNot { it.id == conversation.id } + conversation
        }

        override suspend fun upsertAll(conversations: List<ConversationEntity>) {
            conversations.forEach { upsert(it) }
        }

        override suspend fun delete(conversationId: String) {
            conversations.value = conversations.value.filterNot { it.id == conversationId }
        }

        override suspend fun deleteForAgent(agentId: String) {
            conversations.value = conversations.value.filterNot { it.agentId == agentId }
        }

        override suspend fun deleteForAgentExcept(agentId: String, keepIds: List<String>) {
            conversations.value = conversations.value.filterNot { it.agentId == agentId && it.id !in keepIds }
        }

        override suspend fun getRefreshState(agentId: String): ConversationRefreshEntity? = refreshStates[agentId]

        override suspend fun getAllRefreshStatesOnce(): List<ConversationRefreshEntity> = refreshStates.values.toList()

        override suspend fun upsertRefreshState(state: ConversationRefreshEntity) {
            refreshStates[state.agentId] = state
        }

        override suspend fun deleteAll() {
            conversations.value = emptyList()
        }

        override suspend fun deleteAllRefreshStates() {
            refreshStates.clear()
        }
    }
}
