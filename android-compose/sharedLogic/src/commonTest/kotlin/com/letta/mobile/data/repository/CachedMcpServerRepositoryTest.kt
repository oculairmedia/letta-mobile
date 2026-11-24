package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.McpServerId
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.backend.AppServerRouteUnavailable
import com.letta.mobile.data.repository.api.McpServerRemoteSource
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The MCP catalog reads a session serves: the host's `mcp.list` over Iroh, and one server's tools
 * over the backend seam. Nothing here writes to the catalog — MCP servers are host configuration.
 */
class CachedMcpServerRepositoryTest {
    private val irohConfig = LettaConfig("host", LettaConfig.Mode.SELF_HOSTED, "iroh://node")

    @Test
    fun refreshServersDecodesTheHostMcpListOverAdminRpc() = runTest {
        val transport = McpListTransport(servers = listOf(McpServer(McpServerId("mcp-1"), serverName = "files")))
        val repository = irohRepository(transport)

        repository.refreshServers()

        assertEquals(listOf("mcp.list"), transport.methods)
        assertEquals(listOf("mcp-1"), repository.servers.value.map { it.id.value })
    }

    @Test
    fun refreshServersFailsWhenTheHostReportsAnError() = runTest {
        val transport = McpListTransport(servers = emptyList(), hostError = "unknown admin_rpc mcp.list")

        val failure = assertFailsWith<IllegalStateException> { irohRepository(transport).refreshServers() }

        assertEquals("unknown admin_rpc mcp.list", failure.message)
    }

    @Test
    fun fetchAllMcpToolsFlattensEveryServersToolsUnderTheBackendSeam() = runTest {
        val backend = RecordingMcpSource(
            servers = listOf(McpServer(McpServerId("server-a"), serverName = "a"), McpServer(McpServerId("server-b"), serverName = "b")),
            toolsByServer = mapOf(
                "server-a" to listOf(tool("tool-a")),
                "server-b" to listOf(tool("tool-b")),
            ),
        )
        val repository = CachedMcpServerRepository(RemoteRoute.Bound(backend))

        assertEquals(listOf("tool-a", "tool-b"), repository.fetchAllMcpTools().map { it.id.value })
        assertEquals(listOf("listMcpServers", "listMcpServerTools:server-a", "listMcpServerTools:server-b"), backend.calls)
    }

    @Test
    fun fetchAllMcpToolsReturnsNothingUnderIrohBecauseAwaitingTheHostDeadlocks() = runTest {
        val transport = McpListTransport(servers = listOf(McpServer(McpServerId("mcp-1"), serverName = "files")))
        val backend = RecordingMcpSource(servers = emptyList())
        val repository = CachedMcpServerRepository(RemoteRoute.Bound(backend), IrohAdminRpcMcpSource(transport, irohSettings()))

        assertEquals(emptyList(), repository.fetchAllMcpTools())
        assertEquals(emptyList(), backend.calls)
        assertEquals(listOf("mcp.list"), transport.methods)
    }

    @Test
    fun refreshServersWithoutABoundSourceFailsAsTheMcpServersRoute() = runTest {
        val repository = CachedMcpServerRepository(RemoteRoute.Absent(SessionRoute.MCP_SERVERS))

        val failure = assertFailsWith<AppServerRouteUnavailable> { repository.refreshServers() }

        assertEquals(SessionRoute.MCP_SERVERS, failure.route)
    }

    private fun irohRepository(transport: McpListTransport) =
        CachedMcpServerRepository(RemoteRoute.Absent(SessionRoute.MCP_SERVERS), IrohAdminRpcMcpSource(transport, irohSettings()))

    private fun irohSettings() = ActiveConfigSettingsRepository(irohConfig)

    private fun tool(id: String) = Tool(id = ToolId(id), name = id)

    /**
     * The Iroh channel answering only `mcp.list`, as the host registers it: any other method is a
     * host error, exactly what the wrapper returns for an unregistered admin_rpc.
     */
    private class McpListTransport(
        private val servers: List<McpServer>,
        private val hostError: String? = null,
    ) : NoOpChannelTransport() {
        val methods = mutableListOf<String>()

        override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
            methods += method
            if (method != MCP_LIST || hostError != null) {
                return AppServerInboundFrame.AdminRpcResponse("req", success = false, error = hostError ?: "unknown admin_rpc $method")
            }
            val rows = json.encodeToJsonElement(ListSerializer(McpServer.serializer()), servers)
            return AppServerInboundFrame.AdminRpcResponse("req", success = true, result = rows)
        }
    }

    /** One backend's catalog answer, recording the calls the repository makes. */
    private class RecordingMcpSource(
        private val servers: List<McpServer>,
        private val toolsByServer: Map<String, List<Tool>> = emptyMap(),
    ) : McpServerRemoteSource {
        val calls = mutableListOf<String>()

        override suspend fun listMcpServers(limit: Int?, offset: Int?): List<McpServer> {
            calls += "listMcpServers"
            return servers
        }

        override suspend fun listMcpServerTools(serverId: String): List<Tool> {
            calls += "listMcpServerTools:$serverId"
            return toolsByServer[serverId] ?: emptyList()
        }
    }

    private companion object {
        const val MCP_LIST = "mcp.list"

        /** Encodes the host's `mcp.list` rows, reading them back as leniently as the client does. */
        val json = Json { ignoreUnknownKeys = true }
    }
}
