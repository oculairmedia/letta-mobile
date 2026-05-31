package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.McpServerCreateParams
import com.letta.mobile.data.model.McpServerId
import com.letta.mobile.data.model.McpServerResyncResult
import com.letta.mobile.data.model.McpServerUpdateParams
import com.letta.mobile.data.model.McpToolExecuteParams
import com.letta.mobile.data.model.McpToolExecutionResult
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface IMcpServerRepository {
    val servers: StateFlow<List<McpServer>>
    fun getServers(): Flow<List<McpServer>>
    fun getServerTools(serverId: McpServerId): Flow<List<Tool>>
    suspend fun refreshServers()
    suspend fun refreshServerTools(serverId: McpServerId)
    suspend fun resyncServerTools(serverId: McpServerId): McpServerResyncResult
    suspend fun runServerTool(serverId: McpServerId, toolId: ToolId, params: McpToolExecuteParams): McpToolExecutionResult
    suspend fun fetchAllMcpTools(): List<Tool>
    suspend fun createServer(params: McpServerCreateParams): McpServer
    suspend fun updateServer(id: McpServerId, params: McpServerUpdateParams): McpServer
    suspend fun deleteServer(id: McpServerId)
}
