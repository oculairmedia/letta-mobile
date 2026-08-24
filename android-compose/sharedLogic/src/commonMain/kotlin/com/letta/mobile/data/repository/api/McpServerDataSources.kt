package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.McpServerCreateParams
import com.letta.mobile.data.model.McpServerResyncResult
import com.letta.mobile.data.model.McpServerUpdateParams
import com.letta.mobile.data.model.McpToolExecuteParams
import com.letta.mobile.data.model.McpToolExecutionResult
import com.letta.mobile.data.model.Tool

interface McpServerRemoteSource {
    suspend fun listMcpServers(limit: Int? = null, offset: Int? = null): List<McpServer>
    suspend fun createMcpServer(params: McpServerCreateParams): McpServer
    suspend fun updateMcpServer(serverId: String, params: McpServerUpdateParams): McpServer
    suspend fun deleteMcpServer(serverId: String)
    suspend fun listMcpServerTools(serverId: String): List<Tool>
    suspend fun refreshMcpServerTools(serverId: String): McpServerResyncResult
    suspend fun runMcpServerTool(
        serverId: String,
        toolId: String,
        params: McpToolExecuteParams,
    ): McpToolExecutionResult
}

interface McpServerIrohSource {
    fun shouldUseIroh(): Boolean
    suspend fun listMcpServers(): List<McpServer>
}
