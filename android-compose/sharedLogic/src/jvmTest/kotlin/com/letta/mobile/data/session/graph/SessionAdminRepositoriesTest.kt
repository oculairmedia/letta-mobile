package com.letta.mobile.data.session.graph

import com.letta.mobile.data.backend.AppServerRouteUnavailable
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.ActiveConfigSettingsRepository
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** letta-mobile-by4vm.87: the admin repositories Android and desktop both build. */
class SessionAdminRepositoriesTest {
    private class RecordingTransport : NoOpChannelTransport() {
        val methods = mutableListOf<String>()

        override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
            methods += method
            // One row every admin list here decodes: an MCP server, a tool and a memory block.
            val row = buildJsonObject {
                put("id", "row-1")
                put("server_name", "files")
                put("name", "search")
                put("label", "human")
                put("value", "remembered")
            }
            return AppServerInboundFrame.AdminRpcResponse(requestId = "r", success = true, result = JsonArray(listOf(row)))
        }
    }

    @Test
    fun anIrohBackendServesTheAdminReadsOverAdminRpc() = runTest {
        val transport = RecordingTransport()
        val settings = ActiveConfigSettingsRepository(LettaConfig("host", LettaConfig.Mode.SELF_HOSTED, "iroh://node"))

        val admin = SharedSessionGraph.adminRepositories(transport, settings)
        admin.mcpServerRepository.refreshServers()

        assertEquals(listOf("mcp.list"), transport.methods)
        assertEquals(listOf("row-1"), admin.mcpServerRepository.servers.value.map { it.id.value })
    }

    /** letta-mobile-by4vm.103: tools and memory blocks come from the same builder on both platforms. */
    @Test
    fun anIrohBackendServesToolsAndMemoryBlocksOverAdminRpc() = runTest {
        val transport = RecordingTransport()
        val settings = ActiveConfigSettingsRepository(LettaConfig("host", LettaConfig.Mode.SELF_HOSTED, "iroh://node"))

        val admin = SharedSessionGraph.adminRepositories(transport, settings)
        admin.toolRepository.refreshTools()
        val blocks = admin.blockRepository.getBlocks("agent-1")

        assertEquals(listOf("tool.list", "block.list_agent"), transport.methods)
        assertEquals(listOf("row-1"), admin.toolRepository.getTools().value.map { it.id.value })
        assertEquals(listOf("remembered"), blocks.map { it.value })
    }

    @Test
    fun withoutSettingsNoIrohSourceIsBoundAndTheRouteIsAbsent() = runTest {
        val transport = RecordingTransport()

        val admin = SharedSessionGraph.adminRepositories(transport, settings = null)
        val failure = assertFailsWith<AppServerRouteUnavailable> { admin.mcpServerRepository.refreshServers() }

        assertEquals(SessionRoute.MCP_SERVERS, failure.route)
        val toolFailure = assertFailsWith<AppServerRouteUnavailable> { admin.toolRepository.refreshTools() }
        assertEquals(SessionRoute.TOOLS, toolFailure.route)
        val blockFailure = assertFailsWith<AppServerRouteUnavailable> { admin.blockRepository.getBlocks("agent-1") }
        assertEquals(SessionRoute.BLOCKS, blockFailure.route)
        assertEquals(emptyList(), transport.methods)
    }
}
