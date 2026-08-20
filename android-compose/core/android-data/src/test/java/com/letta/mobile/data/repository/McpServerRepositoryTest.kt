package com.letta.mobile.data.repository

import com.letta.mobile.data.api.IrohAdminApiUnavailableException
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.McpServerId
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.FakeMcpServerApi
import com.letta.mobile.testutil.FakeSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class McpServerRepositoryTest {

    private lateinit var fakeApi: FakeMcpServerApi
    private lateinit var repository: McpServerRepository

    @Before
    fun setup() {
        fakeApi = FakeMcpServerApi()
        repository = McpServerRepository(fakeApi)
    }

    @Test
    fun `refreshServers calls API`() = runTest {
        fakeApi.servers.add(McpServer(
            id = McpServerId("mcp1"),
            serverName = "Test Server",
            serverUrl = "https://example.com",
        ))
        repository.refreshServers()
        assertEquals(1, fakeApi.calls.size)
    }

    // ─── Iroh Purity Tests (letta-mobile client batch) ────────────────────────

    @Test(expected = IrohAdminApiUnavailableException::class)
    fun `refreshServers in iroh mode without source throws IrohAdminApiUnavailableException`() = runTest {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "test",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://test-node",
                accessToken = "token",
            )
        )
        val apiThatThrows = object : FakeMcpServerApi() {
            override suspend fun listMcpServers(limit: Int?, offset: Int?): List<McpServer> {
                throw IrohAdminApiUnavailableException("Raw HTTP forbidden in iroh:// mode")
            }
        }
        val repo = McpServerRepository(apiThatThrows)
        repo.refreshServers()
    }

    @Test
    fun `refreshServers in iroh mode routes via admin_rpc`() = runTest {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "test",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://test-node",
                accessToken = "token",
            )
        )
        val transport = FakeChannelTransport()
        val testServers = listOf(
            McpServer(
                id = McpServerId("mcp1"),
                serverName = "Server 1",
                serverUrl = "https://example1.com",
            ),
            McpServer(
                id = McpServerId("mcp2"),
                serverName = "Server 2",
                serverUrl = "https://example2.com",
            ),
        )
        transport.adminRpcHandler = { method, path, body ->
            assertEquals("mcp.list", method)
            assertEquals("/v1/mcp/servers", path)
            val json = Json { ignoreUnknownKeys = true }
            AppServerInboundFrame.AdminRpcResponse(
                requestId = "req",
                success = true,
                result = json.encodeToJsonElement(ListSerializer(McpServer.serializer()), testServers),
                error = null,
            )
        }
        val irohSource = IrohAdminRpcMcpSource(transport, settings)
        val apiThatThrows = object : FakeMcpServerApi() {
            override suspend fun listMcpServers(limit: Int?, offset: Int?): List<McpServer> {
                throw IrohAdminApiUnavailableException("Raw HTTP forbidden")
            }
        }
        val repo = McpServerRepository(apiThatThrows, irohSource)
        repo.refreshServers()
        assertEquals(2, repo.servers.value.size)
        assertEquals(1, transport.adminRpcCalls.size)
    }
}
