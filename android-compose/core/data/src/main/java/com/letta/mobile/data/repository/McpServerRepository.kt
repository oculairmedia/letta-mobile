package com.letta.mobile.data.repository

import com.letta.mobile.data.api.McpServerApi
import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.McpServerCreateParams
import com.letta.mobile.data.model.McpServerId
import com.letta.mobile.data.model.McpServerResyncResult
import com.letta.mobile.data.model.McpServerUpdateParams
import com.letta.mobile.data.model.McpToolExecuteParams
import com.letta.mobile.data.model.McpToolExecutionResult
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.repository.api.IMcpServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject

open class McpServerRepository @Inject constructor(
    private val mcpServerApi: McpServerApi,
) : IMcpServerRepository {
    private val _servers = MutableStateFlow<List<McpServer>>(emptyList())
    override val servers: StateFlow<List<McpServer>> = _servers.asStateFlow()

    private val _toolsByServer = MutableStateFlow<Map<McpServerId, List<Tool>>>(emptyMap())

    override open fun getServers(): Flow<List<McpServer>> = servers

    override open fun getServerTools(serverId: McpServerId): Flow<List<Tool>> {
        return _toolsByServer.map { it[serverId] ?: emptyList() }
    }

    override open suspend fun refreshServers() {
        _servers.update { mcpServerApi.listMcpServers() }
    }

    override open suspend fun refreshServerTools(serverId: McpServerId) {
        val tools = mcpServerApi.listMcpServerTools(serverId.value)
        _toolsByServer.update { current -> current.toMutableMap().apply {
                    put(serverId, tools)
                } }
    }

    override open suspend fun resyncServerTools(serverId: McpServerId): McpServerResyncResult {
        val result = mcpServerApi.refreshMcpServerTools(serverId.value)
        refreshServerTools(serverId)
        return result
    }

    override open suspend fun runServerTool(
        serverId: McpServerId,
        toolId: ToolId,
        params: McpToolExecuteParams,
    ): McpToolExecutionResult {
        return mcpServerApi.runMcpServerTool(serverId.value, toolId.value, params)
    }

    override open suspend fun fetchAllMcpTools(): List<Tool> {
        refreshServers()
        return _servers.value.flatMap { server ->
            try {
                mcpServerApi.listMcpServerTools(server.id.value)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    override open suspend fun createServer(params: McpServerCreateParams): McpServer {
        val server = mcpServerApi.createMcpServer(params)
        refreshServers()
        return server
    }

    override open suspend fun updateServer(id: McpServerId, params: McpServerUpdateParams): McpServer {
        val server = mcpServerApi.updateMcpServer(id.value, params)
        refreshServers()
        return server
    }

    override open suspend fun deleteServer(id: McpServerId) {
        mcpServerApi.deleteMcpServer(id.value)
        refreshServers()
        _toolsByServer.update { current -> current.toMutableMap().apply {
                    remove(id)
                } }
    }
}
