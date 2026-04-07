package com.letta.mobile.data.api

import com.letta.mobile.data.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolApi @Inject constructor(
    private val apiClient: LettaApiClient
) {
    suspend fun listTools(
        tags: List<String>? = null,
        limit: Int? = null,
        offset: Int? = null
    ): List<Tool> {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        return client.get("$baseUrl/v1/tools") {
            tags?.forEach { parameter("tags", it) }
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()
    }

    suspend fun getTool(toolId: String): Tool {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        return client.get("$baseUrl/v1/tools/$toolId").body()
    }

    suspend fun createTool(params: ToolCreateParams): Tool {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        return client.post("$baseUrl/v1/tools") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }.body()
    }

    suspend fun upsertTool(params: ToolCreateParams): Tool {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        return client.put("$baseUrl/v1/tools") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }.body()
    }

    suspend fun attachTool(agentId: String, toolId: String) {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        client.patch("$baseUrl/v1/agents/$agentId/tools/attach/$toolId") {
            contentType(ContentType.Application.Json)
        }
    }

    suspend fun detachTool(agentId: String, toolId: String) {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        client.patch("$baseUrl/v1/agents/$agentId/tools/detach/$toolId") {
            contentType(ContentType.Application.Json)
        }
    }
}
