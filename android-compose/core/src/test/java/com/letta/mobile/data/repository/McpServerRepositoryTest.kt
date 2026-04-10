package com.letta.mobile.data.repository

import com.letta.mobile.data.model.McpServerCreateParams
import com.letta.mobile.data.model.McpServerResyncResult
import com.letta.mobile.data.model.McpServerUpdateParams
import com.letta.mobile.data.model.McpToolExecuteParams
import com.letta.mobile.data.model.effectiveServerType
import com.letta.mobile.testutil.FakeMcpServerApi
import com.letta.mobile.testutil.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McpServerRepositoryTest {

    private lateinit var fakeApi: FakeMcpServerApi
    private lateinit var repository: McpServerRepository

    @Before
    fun setup() {
        fakeApi = FakeMcpServerApi()
        repository = McpServerRepository(fakeApi)
    }

    @Test
    fun `refreshServers updates StateFlow`() = runTest {
        fakeApi.servers.addAll(listOf(TestData.mcpServer(id = "1"), TestData.mcpServer(id = "2")))
        repository.refreshServers()
        assertEquals(2, repository.servers.value.size)
    }

    @Test
    fun `createServer adds and refreshes`() = runTest {
        val params = McpServerCreateParams(serverName = "New Server", config = buildJsonObject {})
        val server = repository.createServer(params)
        assertEquals("New Server", server.serverName)
        assertTrue(fakeApi.calls.any { it.startsWith("createMcpServer") })
    }

    @Test
    fun `updateServer updates and refreshes`() = runTest {
        fakeApi.servers.add(TestData.mcpServer(id = "s1", serverName = "Old Server"))
        val params = McpServerUpdateParams(
            serverName = "Updated Server",
            config = buildJsonObject {
                put("type", "streamable_http")
                put("mcp_server_type", "streamable_http")
                put("server_url", "https://example.com/mcp")
                put("auth_header", "Authorization")
            }
        )

        val updated = repository.updateServer("s1", params)

        assertEquals("Updated Server", updated.serverName)
        assertEquals("https://example.com/mcp", updated.serverUrl)
        assertEquals("Authorization", updated.authHeader)
        assertEquals("streamable_http", updated.effectiveServerType())
        assertTrue(fakeApi.calls.contains("updateMcpServer:s1"))
    }

    @Test
    fun `deleteServer removes and cleans tools`() = runTest {
        fakeApi.servers.add(TestData.mcpServer(id = "s1"))
        repository.refreshServers()
        repository.deleteServer("s1")
        assertTrue(repository.servers.value.none { it.id == "s1" })
    }

    @Test
    fun `refreshServerTools loads tools`() = runTest {
        fakeApi.serverTools["s1"] = listOf(TestData.tool(id = "t1"))
        repository.refreshServerTools("s1")
        assertTrue(fakeApi.calls.contains("listMcpServerTools:s1"))
    }

    @Test
    fun `getServers returns empty initially`() = runTest {
        assertTrue(repository.servers.value.isEmpty())
    }

    @Test
    fun `resyncServerTools returns backend summary and refreshes cache`() = runTest {
        val refreshedTools = listOf(TestData.tool(id = "tool-2", name = "updatedTool"))
        fakeApi.serverTools["s1"] = refreshedTools
        fakeApi.resyncResults["s1"] = McpServerResyncResult(
            deleted = listOf("oldTool"),
            updated = listOf("updatedTool"),
            added = listOf("newTool"),
        )

        val result = repository.resyncServerTools("s1")

        assertEquals(listOf("oldTool"), result.deleted)
        assertEquals(listOf("updatedTool"), result.updated)
        assertEquals(listOf("newTool"), result.added)
        assertTrue(fakeApi.calls.contains("refreshMcpServerTools:s1"))
    }

    @Test
    fun `runServerTool delegates args and returns execution result`() = runTest {
        fakeApi.toolExecutionResults["s1" to "tool-1"] = TestData.mcpToolExecutionResult(status = "success")

        val result = repository.runServerTool(
            serverId = "s1",
            toolId = "tool-1",
            params = McpToolExecuteParams(
                buildJsonObject {
                    put("query", "hello")
                    putJsonObject("filters") {
                        put("enabled", "true")
                    }
                }
            ),
        )

        assertEquals("success", result.status)
        assertTrue(fakeApi.calls.any { it.startsWith("runMcpServerTool:s1:tool-1:") })
    }

    @Test(expected = com.letta.mobile.data.api.ApiException::class)
    fun `refreshServers throws on API failure`() = runTest {
        fakeApi.shouldFail = true
        repository.refreshServers()
    }
}
