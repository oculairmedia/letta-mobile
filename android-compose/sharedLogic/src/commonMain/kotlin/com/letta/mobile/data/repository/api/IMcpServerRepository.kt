package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.Tool
import kotlinx.coroutines.flow.StateFlow

/** The MCP server catalog the tool library lists. Reads only: MCP servers are host configuration. */
interface IMcpServerRepository {
    val servers: StateFlow<List<McpServer>>
    suspend fun refreshServers()
    suspend fun fetchAllMcpTools(): List<Tool>
}
