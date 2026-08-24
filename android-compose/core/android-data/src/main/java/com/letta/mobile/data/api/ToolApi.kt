package com.letta.mobile.data.api

import com.letta.mobile.data.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class ToolApi @Inject constructor(
    private val apiClient: LettaApiClient
) {
    open suspend fun listTools(
        tags: List<String>? = null,
        limit: Int? = null,
        offset: Int? = null
    ): List<Tool> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/tools") {
            tags?.forEach { parameter("tags", it) }
            parameter("limit", limit)
            parameter("offset", offset)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun getTool(toolId: String): Tool {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/tools/$toolId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun countTools(): Int {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/tools/count")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun createTool(params: ToolCreateParams): Tool {
        val (client, baseUrl) = apiClient.session()

        val response = client.post("$baseUrl/v1/tools") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun upsertTool(params: ToolCreateParams): Tool {
        val (client, baseUrl) = apiClient.session()

        val response = client.put("$baseUrl/v1/tools") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun updateTool(toolId: String, params: ToolUpdateParams): Tool {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/tools/$toolId") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun generateJsonSchema(params: ToolSchemaGenerateParams): kotlinx.serialization.json.JsonObject {
        val (client, baseUrl) = apiClient.session()

        val response = client.post("$baseUrl/v1/tools/generate-schema") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun deleteTool(toolId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.delete("$baseUrl/v1/tools/$toolId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    open suspend fun attachTool(agentId: String, toolId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/agents/$agentId/tools/attach/$toolId") {
            contentType(ContentType.Application.Json)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    open suspend fun detachTool(agentId: String, toolId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/agents/$agentId/tools/detach/$toolId") {
            contentType(ContentType.Application.Json)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }
}
