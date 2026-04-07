package com.letta.mobile.data.api

import com.letta.mobile.data.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class McpServerApi @Inject constructor(
    private val apiClient: LettaApiClient
) {
    suspend fun listMcpServers(
        limit: Int? = null,
        offset: Int? = null
    ): List<McpServer> {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        return client.get("$baseUrl/v1/mcp-servers") {
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()
    }

    suspend fun getMcpServer(serverId: String): McpServer {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        return client.get("$baseUrl/v1/mcp-servers/$serverId").body()
    }

    suspend fun createMcpServer(params: McpServerCreateParams): McpServer {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        return client.post("$baseUrl/v1/mcp-servers") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }.body()
    }

    suspend fun deleteMcpServer(serverId: String) {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        client.delete("$baseUrl/v1/mcp-servers/$serverId")
    }

    suspend fun listMcpServerTools(serverId: String): List<Tool> {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        return client.get("$baseUrl/v1/mcp-servers/$serverId/tools").body()
    }
}
