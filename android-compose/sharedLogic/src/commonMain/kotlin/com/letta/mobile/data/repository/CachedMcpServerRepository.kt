package com.letta.mobile.data.repository

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
import com.letta.mobile.data.repository.api.McpServerIrohSource
import com.letta.mobile.data.repository.api.McpServerRemoteSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Phase 5l: platform-neutral cached MCP server repository. */
open class CachedMcpServerRepository(
    private val remote: McpServerRemoteSource,
    private val irohMcpSource: McpServerIrohSource? = null,
) : IMcpServerRepository {
    private val _servers = MutableStateFlow<List<McpServer>>(emptyList())
    override val servers: StateFlow<List<McpServer>> = _servers.asStateFlow()

    private val _toolsByServer = MutableStateFlow<Map<McpServerId, List<Tool>>>(emptyMap())

    override fun getServers(): Flow<List<McpServer>> = servers

    override fun getServerTools(serverId: McpServerId): Flow<List<Tool>> {
        return _toolsByServer.map { it[serverId] ?: emptyList() }
    }

    override suspend fun refreshServers() {
        val irohSource = irohMcpSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            _servers.update { irohSource.listMcpServers() }
            return
        }
        _servers.update { remote.listMcpServers() }
    }

    override suspend fun refreshServerTools(serverId: McpServerId) {
        val tools = remote.listMcpServerTools(serverId.value)
        _toolsByServer.update { current ->
            current.toMutableMap().apply { put(serverId, tools) }
        }
    }

    override suspend fun resyncServerTools(serverId: McpServerId): McpServerResyncResult {
        val result = remote.refreshMcpServerTools(serverId.value)
        refreshServerTools(serverId)
        return result
    }

    override suspend fun runServerTool(
        serverId: McpServerId,
        toolId: ToolId,
        params: McpToolExecuteParams,
    ): McpToolExecutionResult {
        return remote.runMcpServerTool(serverId.value, toolId.value, params)
    }

    override suspend fun fetchAllMcpTools(): List<Tool> {
        refreshServers()
        return _servers.value.flatMap { server ->
            try {
                remote.listMcpServerTools(server.id.value)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    override suspend fun createServer(params: McpServerCreateParams): McpServer {
        val server = remote.createMcpServer(params)
        refreshServers()
        return server
    }

    override suspend fun updateServer(id: McpServerId, params: McpServerUpdateParams): McpServer {
        val server = remote.updateMcpServer(id.value, params)
        refreshServers()
        return server
    }

    override suspend fun deleteServer(id: McpServerId) {
        remote.deleteMcpServer(id.value)
        refreshServers()
        _toolsByServer.update { current ->
            current.toMutableMap().apply { remove(id) }
        }
    }
}
