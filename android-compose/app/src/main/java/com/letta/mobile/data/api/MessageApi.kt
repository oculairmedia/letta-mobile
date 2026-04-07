package com.letta.mobile.data.api

import com.letta.mobile.data.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageApi @Inject constructor(
    private val apiClient: LettaApiClient
) {
    suspend fun sendMessage(agentId: String, request: MessageCreateRequest): LettaResponse {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        return client.post("$baseUrl/v1/agents/$agentId/messages") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest
    ): ByteReadChannel {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.post("$baseUrl/v1/conversations/$conversationId/messages") {
            contentType(ContentType.Application.Json)
            setBody(request.copy(streaming = true))
        }

        return response.body()
    }

    suspend fun listMessages(
        agentId: String,
        limit: Int? = null,
        after: String? = null
    ): List<LettaMessage> {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        return client.get("$baseUrl/v1/agents/$agentId/messages") {
            parameter("limit", limit)
            parameter("after", after)
        }.body()
    }

    suspend fun resetMessages(agentId: String) {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        client.patch("$baseUrl/v1/agents/$agentId/reset-messages") {
            contentType(ContentType.Application.Json)
        }
    }
}
