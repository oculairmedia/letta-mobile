package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.McpServerCreateParams
import com.letta.mobile.data.model.McpServerResyncResult
import com.letta.mobile.data.model.McpServerUpdateParams
import com.letta.mobile.data.model.McpToolExecuteParams
import com.letta.mobile.data.model.McpToolExecutionResult
import com.letta.mobile.data.model.Tool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface IMcpServerRepository {
    val servers: StateFlow<List<McpServer>>
    fun getServers(): Flow<List<McpServer>>
    fun getServerTools(serverId: String): Flow<List<Tool>>
    suspend fun refreshServers()
    suspend fun refreshServerTools(serverId: String)
    suspend fun resyncServerTools(serverId: String): McpServerResyncResult
    suspend fun runServerTool(serverId: String, toolId: String, params: McpToolExecuteParams): McpToolExecutionResult
    suspend fun fetchAllMcpTools(): List<Tool>
    suspend fun createServer(params: McpServerCreateParams): McpServer
    suspend fun updateServer(id: String, params: McpServerUpdateParams): McpServer
    suspend fun deleteServer(id: String)
}
