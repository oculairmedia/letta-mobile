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
import com.letta.mobile.util.runCatchingCancellable
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
        if (inIrohMode()) irohUnsupported("mcp.refreshServerTools(${serverId.value})")
        val tools = remote.listMcpServerTools(serverId.value)
        replaceServerTools(serverId, tools)
    }

    override suspend fun resyncServerTools(serverId: McpServerId): McpServerResyncResult {
        if (inIrohMode()) irohUnsupported("mcp.resyncServerTools(${serverId.value})")
        val result = remote.refreshMcpServerTools(serverId.value)
        runCatchingCancellable { refreshServerTools(serverId) }
        return result
    }

    override suspend fun runServerTool(
        serverId: McpServerId,
        toolId: ToolId,
        params: McpToolExecuteParams,
    ): McpToolExecutionResult {
        if (inIrohMode()) irohUnsupported("mcp.runServerTool(${serverId.value}, ${toolId.value})")
        return remote.runMcpServerTool(serverId.value, toolId.value, params)
    }

    override suspend fun fetchAllMcpTools(): List<Tool> {
        if (inIrohMode()) {
            refreshServers()
            return emptyList()
        }
        refreshServers()
        return _servers.value.flatMap { server ->
            runCatchingCancellable {
                remote.listMcpServerTools(server.id.value)
            }.getOrElse { emptyList() }
        }
    }

    override suspend fun createServer(params: McpServerCreateParams): McpServer {
        if (inIrohMode()) irohUnsupported("mcp.createServer")
        val server = remote.createMcpServer(params)
        upsertServer(server)
        return server
    }

    override suspend fun updateServer(id: McpServerId, params: McpServerUpdateParams): McpServer {
        if (inIrohMode()) irohUnsupported("mcp.updateServer(${id.value})")
        val server = remote.updateMcpServer(id.value, params)
        upsertServer(server)
        return server
    }

    override suspend fun deleteServer(id: McpServerId) {
        if (inIrohMode()) irohUnsupported("mcp.deleteServer(${id.value})")
        remote.deleteMcpServer(id.value)
        _servers.update { current -> current.filterNot { it.id == id } }
        _toolsByServer.update { current ->
            current.toMutableMap().apply { remove(id) }
        }
    }

    private fun inIrohMode(): Boolean = irohMcpSource?.shouldUseIroh() == true

    private fun irohUnsupported(operation: String): Nothing =
        throw UnsupportedOperationException("Iroh admin_rpc does not support $operation yet")

    private fun upsertServer(server: McpServer) {
        _servers.update { current ->
            val index = current.indexOfFirst { it.id == server.id }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = server }
            } else {
                current + server
            }
        }
    }

    private fun replaceServerTools(serverId: McpServerId, tools: List<Tool>) {
        _toolsByServer.update { current ->
            current.toMutableMap().apply { put(serverId, tools) }
        }
    }
}
