package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.Tool

/**
 * The MCP catalog a non-App-Server backend would serve: its server list and one server's tools.
 * MCP servers are host process configuration, so nothing here creates, updates or runs one.
 */
interface McpServerRemoteSource {
    suspend fun listMcpServers(limit: Int? = null, offset: Int? = null): List<McpServer>
    suspend fun listMcpServerTools(serverId: String): List<Tool>
}

interface McpServerIrohSource {
    fun shouldUseIroh(): Boolean
    suspend fun listMcpServers(): List<McpServer>
}
