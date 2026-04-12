package com.letta.mobile.ui.screens.mcp

import app.cash.turbine.test
import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.McpServerCreateParams
import com.letta.mobile.data.model.McpServerResyncResult
import com.letta.mobile.data.model.McpServerUpdateParams
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.effectiveAuthHeader
import com.letta.mobile.data.model.effectiveAuthToken
import com.letta.mobile.data.model.effectiveCustomHeaders
import com.letta.mobile.data.model.effectiveEnv
import com.letta.mobile.data.repository.McpServerRepository
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.testutil.FakeMcpServerApi
import com.letta.mobile.testutil.FakeToolApi
import com.letta.mobile.testutil.TestData
import com.letta.mobile.ui.common.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McpViewModelTest {

    private lateinit var fakeMcpRepo: FakeMcpRepo
    private lateinit var fakeToolRepo: FakeToolRepo
    private lateinit var viewModel: McpViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeMcpRepo = FakeMcpRepo()
        fakeToolRepo = FakeToolRepo()
        viewModel = McpViewModel(fakeMcpRepo, fakeToolRepo)
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `loadData sets Success with servers and tools`() = runTest {
        val server = TestData.mcpServer(id = "s1")
        val tool = TestData.tool(id = "t1")
        fakeMcpRepo.setServers(listOf(server))
        fakeMcpRepo.setServerTools("s1", listOf(tool))
        fakeToolRepo.setTools(listOf(tool))
        viewModel.loadData()
        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertEquals(1, state.data.servers.size)
            assertEquals(1, state.data.allTools.size)
            assertEquals(1, state.data.serverTools.size)
            assertEquals(listOf(tool), state.data.serverTools["s1"])
        }
    }

    @Test
    fun `loadData sets Error on failure`() = runTest {
        fakeMcpRepo.shouldFail = true
        viewModel.loadData()
        viewModel.uiState.test { assertTrue(awaitItem() is UiState.Error) }
    }

    @Test
    fun `selectTab updates selectedTab`() = runTest {
        fakeMcpRepo.setServers(emptyList())
        viewModel.loadData()
        viewModel.selectTab(1)
        viewModel.uiState.test {
            assertEquals(1, (awaitItem() as UiState.Success).data.selectedTab)
        }
    }

    @Test
    fun `deleteServer calls repo`() = runTest {
        fakeMcpRepo.setServers(listOf(TestData.mcpServer(id = "s1")))
        viewModel.loadData()
        viewModel.deleteServer("s1")
        assertTrue(fakeMcpRepo.deleteCalls.contains("s1"))
    }

    @Test
    fun `addServer calls repo`() = runTest {
        viewModel.addServer(
            McpServerCreateParams(
                serverName = "New",
                config = buildJsonObject {
                    put("mcp_server_type", "streamable_http")
                    put("server_url", "http://localhost")
                }
            )
        )
        assertTrue(fakeMcpRepo.createCalls.isNotEmpty())
    }

    @Test
    fun `updateServer calls repo`() = runTest {
        fakeMcpRepo.setServers(listOf(TestData.mcpServer(id = "s1")))
        viewModel.updateServer(
            "s1",
            McpServerUpdateParams(
                serverName = "Updated",
                config = buildJsonObject {
                    put("mcp_server_type", "stdio")
                    put("command", "python")
                }
            )
        )
        assertTrue(fakeMcpRepo.updateCalls.contains("s1"))
    }

    @Test
    fun `loadData loads tools per server`() = runTest {
        val server1 = TestData.mcpServer(id = "s1", serverName = "Server 1")
        val server2 = TestData.mcpServer(id = "s2", serverName = "Server 2")
        val toolsForS1 = listOf(TestData.tool(id = "t1", name = "tool1"))
        val toolsForS2 = listOf(TestData.tool(id = "t2", name = "tool2"))
        
        fakeMcpRepo.setServers(listOf(server1, server2))
        fakeMcpRepo.setServerTools("s1", toolsForS1)
        fakeMcpRepo.setServerTools("s2", toolsForS2)
        fakeToolRepo.setTools(emptyList())
        
        viewModel.loadData()
        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertEquals(2, state.data.servers.size)
            assertEquals(2, state.data.serverTools.size)
            assertEquals(toolsForS1, state.data.serverTools["s1"])
            assertEquals(toolsForS2, state.data.serverTools["s2"])
        }
    }

    @Test
    fun `checkServer marks server reachable on success`() = runTest {
        val server = TestData.mcpServer(id = "s1")
        fakeMcpRepo.setServers(listOf(server))
        fakeMcpRepo.setServerTools("s1", listOf(TestData.tool(id = "t1")))
        fakeMcpRepo.resyncResult = McpServerResyncResult(added = listOf("t1"))
        viewModel.loadData()

        viewModel.checkServer("s1")

        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertTrue(state.data.serverChecks["s1"]?.isReachable == true)
            assertTrue(state.data.serverChecks["s1"]?.message?.contains("+1 added") == true)
        }
    }

    @Test
    fun `checkServer marks server unreachable on failure`() = runTest {
        val server = TestData.mcpServer(id = "s1")
        fakeMcpRepo.setServers(listOf(server))
        viewModel.loadData()

        fakeMcpRepo.failServerChecks = true

        viewModel.checkServer("s1")

        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertTrue(state.data.serverChecks["s1"]?.isReachable == false)
        }
    }

    @Test
    fun `loadData maps tools to parent MCP server`() = runTest {
        val server = TestData.mcpServer(id = "s1", serverName = "Server 1")
        val tool = TestData.tool(id = "t1", name = "tool1")
        fakeMcpRepo.setServers(listOf(server))
        fakeMcpRepo.setServerTools("s1", listOf(tool))
        fakeToolRepo.setTools(listOf(tool))

        viewModel.loadData()

        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertEquals("s1", state.data.toolParents["t1"]?.serverId)
            assertEquals("Server 1", state.data.toolParents["t1"]?.serverName)
        }
    }

    @Test
    fun `server parity fields survive loadData`() = runTest {
        val server = TestData.mcpServer(id = "s1", serverName = "Server 1").copy(
            authHeader = "Authorization",
            authToken = "Bearer secret",
            customHeaders = mapOf("X-Test" to "true"),
            env = mapOf("TOKEN" to "abc"),
            organizationId = "org-1",
            createdById = "user-1",
            lastUpdatedById = "user-2",
        )
        fakeMcpRepo.setServers(listOf(server))
        fakeToolRepo.setTools(emptyList())

        viewModel.loadData()

        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            val loaded = state.data.servers.first()
            assertEquals("Authorization", loaded.effectiveAuthHeader())
            assertEquals("Bearer secret", loaded.effectiveAuthToken())
            assertEquals("true", loaded.effectiveCustomHeaders()?.get("X-Test"))
            assertEquals("abc", loaded.effectiveEnv()?.get("TOKEN"))
            assertEquals("org-1", loaded.organizationId)
            assertEquals("user-1", loaded.createdById)
            assertEquals("user-2", loaded.lastUpdatedById)
        }
    }

    private class FakeMcpRepo : McpServerRepository(FakeMcpServerApi()) {
        private val _servers = MutableStateFlow<List<McpServer>>(emptyList())
        private val _toolsByServer = MutableStateFlow<Map<String, List<Tool>>>(emptyMap())
        override val servers: StateFlow<List<McpServer>> = _servers.asStateFlow()
        var shouldFail = false
        var failServerChecks = false
        var resyncResult = McpServerResyncResult()
        val deleteCalls = mutableListOf<String>()
        val createCalls = mutableListOf<String>()
        val updateCalls = mutableListOf<String>()

        fun setServers(list: List<McpServer>) { _servers.value = list }
        fun setServerTools(serverId: String, tools: List<Tool>) {
            _toolsByServer.value = _toolsByServer.value.toMutableMap().apply {
                put(serverId, tools)
            }
        }
        override suspend fun refreshServers() { if (shouldFail) throw Exception("Failed") }
        override suspend fun refreshServerTools(serverId: String) {
            if (failServerChecks) throw Exception("check failed")
        }
        override suspend fun resyncServerTools(serverId: String): McpServerResyncResult {
            if (failServerChecks) throw Exception("check failed")
            return resyncResult
        }
        override fun getServerTools(serverId: String): Flow<List<Tool>> {
            return _toolsByServer.map { it[serverId] ?: emptyList() }
        }
        override suspend fun deleteServer(id: String) {
            deleteCalls.add(id)
            _servers.value = _servers.value.filter { it.id != id }
            _toolsByServer.value = _toolsByServer.value.toMutableMap().apply { remove(id) }
        }
        override suspend fun createServer(params: McpServerCreateParams): McpServer {
            createCalls.add(params.serverName)
            return TestData.mcpServer(serverName = params.serverName)
        }
        override suspend fun updateServer(id: String, params: McpServerUpdateParams): McpServer {
            updateCalls.add(id)
            val updated = TestData.mcpServer(
                id = id,
                serverName = params.serverName ?: "Updated",
                serverUrl = params.config?.get("server_url")?.toString()?.trim('"')
            )
            _servers.value = _servers.value.map { if (it.id == id) updated else it }
            return updated
        }
    }

    private class FakeToolRepo : ToolRepository(FakeToolApi()) {
        private val _tools = MutableStateFlow<List<Tool>>(emptyList())
        fun setTools(list: List<Tool>) { _tools.value = list }
        override fun getTools(): StateFlow<List<Tool>> = _tools.asStateFlow()
        override suspend fun refreshTools() {}
    }
}
