package com.letta.mobile.data.api

import com.letta.mobile.data.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentApi @Inject constructor(
    private val apiClient: LettaApiClient
) {
    suspend fun listAgents(
        limit: Int? = null,
        offset: Int? = null,
        tags: List<String>? = null
    ): List<Agent> {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        return client.get("$baseUrl/v1/agents") {
            parameter("limit", limit)
            parameter("offset", offset)
            tags?.forEach { parameter("tags", it) }
        }.body()
    }

    suspend fun getAgent(agentId: String): Agent {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        return client.get("$baseUrl/v1/agents/$agentId").body()
    }

    suspend fun createAgent(params: AgentCreateParams): Agent {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        return client.post("$baseUrl/v1/agents") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }.body()
    }

    suspend fun updateAgent(agentId: String, params: AgentUpdateParams): Agent {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        return client.patch("$baseUrl/v1/agents/$agentId") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }.body()
    }

    suspend fun deleteAgent(agentId: String) {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        client.delete("$baseUrl/v1/agents/$agentId")
    }

    suspend fun exportAgent(agentId: String): String {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        return client.get("$baseUrl/v1/agents/$agentId/export").body()
    }
}
