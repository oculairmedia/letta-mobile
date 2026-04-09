package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.McpServerApi
import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.McpServerCreateParams
import com.letta.mobile.data.model.McpServerUpdateParams
import com.letta.mobile.data.model.Tool

class FakeMcpServerApi : McpServerApi(null!!) {
    var servers = mutableListOf<McpServer>()
    var serverTools = mutableMapOf<String, List<Tool>>()
    var shouldFail = false
    val calls = mutableListOf<String>()

    override suspend fun listMcpServers(): List<McpServer> {
        calls.add("listMcpServers")
        if (shouldFail) throw ApiException(500, "Server error")
        return servers.toList()
    }

    override suspend fun createMcpServer(params: McpServerCreateParams): McpServer {
        calls.add("createMcpServer:${params.serverName}")
        if (shouldFail) throw ApiException(500, "Server error")
        val config = params.config
        val server = McpServer(
            id = "new-${servers.size}",
            serverName = params.serverName,
            serverUrl = config["server_url"]?.toString()?.trim('"'),
            command = config["command"]?.toString()?.trim('"'),
            args = config["args"]?.toString()
                ?.removePrefix("[")
                ?.removeSuffix("]")
                ?.split(",")
                ?.map { it.trim().trim('"') }
                ?.filter { it.isNotBlank() },
            mcpServerType = config["mcp_server_type"]?.toString()?.trim('"'),
            config = config,
        )
        servers.add(server)
        return server
    }

    override suspend fun updateMcpServer(serverId: String, params: McpServerUpdateParams): McpServer {
        calls.add("updateMcpServer:$serverId")
        if (shouldFail) throw ApiException(500, "Server error")
        val current = servers.firstOrNull { it.id == serverId } ?: throw ApiException(404, "Server not found")
        val config = params.config ?: current.config
        val updated = current.copy(
            serverName = params.serverName ?: current.serverName,
            serverUrl = config?.get("server_url")?.toString()?.trim('"') ?: current.serverUrl,
            command = config?.get("command")?.toString()?.trim('"') ?: current.command,
            args = config?.get("args")?.toString()
                ?.removePrefix("[")
                ?.removeSuffix("]")
                ?.split(",")
                ?.map { it.trim().trim('"') }
                ?.filter { it.isNotBlank() }
                ?: current.args,
            mcpServerType = config?.get("mcp_server_type")?.toString()?.trim('"') ?: current.mcpServerType,
            config = config,
        )
        servers = servers.map { if (it.id == serverId) updated else it }.toMutableList()
        return updated
    }

    override suspend fun deleteMcpServer(serverId: String) {
        calls.add("deleteMcpServer:$serverId")
        if (shouldFail) throw ApiException(500, "Server error")
        servers.removeAll { it.id == serverId }
    }

    override suspend fun listMcpServerTools(serverId: String): List<Tool> {
        calls.add("listMcpServerTools:$serverId")
        if (shouldFail) throw ApiException(500, "Server error")
        return serverTools[serverId] ?: emptyList()
    }
}
