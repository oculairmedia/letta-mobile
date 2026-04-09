package com.letta.mobile.data.api

import com.letta.mobile.data.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationApi @Inject constructor(
    private val apiClient: LettaApiClient
) {
    suspend fun listConversations(
        agentId: String? = null,
        limit: Int? = null,
        after: String? = null,
        archiveStatus: String? = null,
        summarySearch: String? = null,
        order: String? = null,
        orderBy: String? = null,
    ): List<Conversation> {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.get("$baseUrl/v1/conversations") {
            parameter("agent_id", agentId)
            parameter("limit", limit)
            parameter("after", after)
            parameter("archive_status", archiveStatus)
            parameter("summary_search", summarySearch)
            parameter("order", order)
            parameter("order_by", orderBy)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    suspend fun getConversation(conversationId: String): Conversation {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.get("$baseUrl/v1/conversations/$conversationId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    suspend fun createConversation(params: ConversationCreateParams): Conversation {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.post("$baseUrl/v1/conversations") {
            contentType(ContentType.Application.Json)
            parameter("agent_id", params.agentId)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    suspend fun updateConversation(conversationId: String, params: ConversationUpdateParams): Conversation {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.patch("$baseUrl/v1/conversations/$conversationId") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    suspend fun deleteConversation(conversationId: String) {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.delete("$baseUrl/v1/conversations/$conversationId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    suspend fun forkConversation(conversationId: String, agentId: String? = null): Conversation {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.post("$baseUrl/v1/conversations/$conversationId/fork") {
            contentType(ContentType.Application.Json)
            agentId?.let { parameter("agent_id", it) }
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    suspend fun cancelConversation(conversationId: String, agentId: String? = null) {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.post("$baseUrl/v1/conversations/$conversationId/cancel") {
            contentType(ContentType.Application.Json)
            agentId?.let { parameter("agent_id", it) }
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    suspend fun recompileConversation(
        conversationId: String,
        dryRun: Boolean = false,
        agentId: String? = null,
    ): String {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.post("$baseUrl/v1/conversations/$conversationId/recompile") {
            contentType(ContentType.Application.Json)
            parameter("dry_run", dryRun)
            setBody(
                buildMap<String, String?> {
                    put("agent_id", agentId)
                }.filterValues { it != null }
            )
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }
}
