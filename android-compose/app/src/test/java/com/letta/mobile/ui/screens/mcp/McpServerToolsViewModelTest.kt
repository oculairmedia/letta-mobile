package com.letta.mobile.ui.screens.mcp

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.repository.McpServerRepository
import com.letta.mobile.testutil.FakeMcpServerApi
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McpServerToolsViewModelTest {

    private lateinit var fakeRepo: FakeMcpRepo
    private lateinit var viewModel: McpServerToolsViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeMcpRepo()
        val savedState = SavedStateHandle(mapOf("serverId" to "s1"))
        viewModel = McpServerToolsViewModel(savedState, fakeRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadServerTools loads server and discovered tools`() = runTest {
        val server = TestData.mcpServer(id = "s1", serverName = "Server 1")
        val tool = TestData.tool(id = "t1", name = "tool1")
        fakeRepo.setServers(listOf(server))
        fakeRepo.setServerTools("s1", listOf(tool))

        viewModel.loadServerTools()

        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertEquals("Server 1", state.data.server.serverName)
            assertEquals(1, state.data.tools.size)
        }
    }

    @Test
    fun `loadServerTools sets error when server missing`() = runTest {
        fakeRepo.setServers(emptyList())

        viewModel.loadServerTools()

        viewModel.uiState.test {
            assertTrue(awaitItem() is UiState.Error)
        }
    }

    private class FakeMcpRepo : McpServerRepository(FakeMcpServerApi()) {
        private val _servers = MutableStateFlow<List<McpServer>>(emptyList())
        private val _toolsByServer = MutableStateFlow<Map<String, List<Tool>>>(emptyMap())
        override val servers: StateFlow<List<McpServer>> = _servers.asStateFlow()

        fun setServers(list: List<McpServer>) { _servers.value = list }
        fun setServerTools(serverId: String, tools: List<Tool>) {
            _toolsByServer.value = _toolsByServer.value + (serverId to tools)
        }

        override suspend fun refreshServers() {}
        override suspend fun refreshServerTools(serverId: String) {}
        override fun getServerTools(serverId: String): Flow<List<Tool>> {
            return _toolsByServer.map { it[serverId] ?: emptyList() }
        }
    }
}
