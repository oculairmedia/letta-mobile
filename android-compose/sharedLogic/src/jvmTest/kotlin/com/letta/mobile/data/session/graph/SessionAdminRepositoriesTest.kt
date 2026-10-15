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
            val server = buildJsonObject {
                put("id", "mcp-1")
                put("server_name", "files")
            }
            return AppServerInboundFrame.AdminRpcResponse(requestId = "r", success = true, result = JsonArray(listOf(server)))
        }
    }

    @Test
    fun anIrohBackendServesTheAdminReadsOverAdminRpc() = runTest {
        val transport = RecordingTransport()
        val settings = ActiveConfigSettingsRepository(LettaConfig("host", LettaConfig.Mode.SELF_HOSTED, "iroh://node"))

        val admin = SharedSessionGraph.adminRepositories(transport, settings)
        admin.mcpServerRepository.refreshServers()

        assertEquals(listOf("mcp.list"), transport.methods)
        assertEquals(listOf("mcp-1"), admin.mcpServerRepository.servers.value.map { it.id.value })
    }

    @Test
    fun withoutSettingsNoIrohSourceIsBoundAndTheRouteIsAbsent() = runTest {
        val transport = RecordingTransport()

        val admin = SharedSessionGraph.adminRepositories(transport, settings = null)
        val failure = assertFailsWith<AppServerRouteUnavailable> { admin.mcpServerRepository.refreshServers() }

        assertEquals(SessionRoute.MCP_SERVERS, failure.route)
        assertEquals(emptyList(), transport.methods)
    }
}
