package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.McpServerApi
import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.McpServerCreateParams
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
        val server = TestData.mcpServer(id = "new-${servers.size}", serverName = params.serverName)
        servers.add(server)
        return server
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
