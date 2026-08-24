package com.letta.mobile.data.api

import com.letta.mobile.data.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class McpServerApi @Inject constructor(
    private val apiClient: LettaApiClient
) : com.letta.mobile.data.repository.api.McpServerRemoteSource {
    open override suspend fun listMcpServers(
        limit: Int?,
        offset: Int?
    ): List<McpServer> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/mcp-servers") {
            parameter("limit", limit)
            parameter("offset", offset)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun createMcpServer(params: McpServerCreateParams): McpServer {
        val (client, baseUrl) = apiClient.session()

        val response = client.post("$baseUrl/v1/mcp-servers") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun updateMcpServer(serverId: String, params: McpServerUpdateParams): McpServer {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/mcp-servers/$serverId") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun deleteMcpServer(serverId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.delete("$baseUrl/v1/mcp-servers/$serverId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    open override suspend fun listMcpServerTools(serverId: String): List<Tool> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/mcp-servers/$serverId/tools")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun refreshMcpServerTools(serverId: String): McpServerResyncResult {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/mcp-servers/$serverId/refresh")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun runMcpServerTool(
        serverId: String,
        toolId: String,
        params: McpToolExecuteParams,
    ): McpToolExecutionResult {
        val (client, baseUrl) = apiClient.session()

        val response = client.post("$baseUrl/v1/mcp-servers/$serverId/tools/$toolId/run") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }
}
