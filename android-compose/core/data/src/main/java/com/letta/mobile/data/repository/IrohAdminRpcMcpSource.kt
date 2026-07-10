package com.letta.mobile.data.repository

import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * MCP server reads over the Iroh admin RPC control channel.
 *
 * P4 purity client batch (letta-mobile): server handlers exist (McpAdminHandlers
 * registers mcp.list); this is the client wiring.
 */
class IrohAdminRpcMcpSource(
    private val channelTransport: IChannelTransport,
    private val settingsRepository: ISettingsRepository,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    },
) {
    fun shouldUseIroh(): Boolean =
        settingsRepository.activeBackendIsIroh()

    suspend fun listMcpServers(): List<McpServer> {
        val response = channelTransport.adminRpc(
            method = "mcp.list",
            path = "/v1/mcp/servers",
            body = "{}",
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc mcp.list failed")
        val result = response.result ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(McpServer.serializer()), result)
    }
}
