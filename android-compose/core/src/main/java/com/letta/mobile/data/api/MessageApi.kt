package com.letta.mobile.data.api

import com.letta.mobile.data.model.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class MessageApi @Inject constructor(
    private val apiClient: LettaApiClient
) {
    open suspend fun sendMessage(agentId: String, request: MessageCreateRequest): LettaResponse {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.post("$baseUrl/v1/agents/$agentId/messages") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest
    ): ByteReadChannel {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.post("$baseUrl/v1/conversations/$conversationId/messages") {
            contentType(ContentType.Application.Json)
            setBody(request.copy(streaming = true))
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun listMessages(
        agentId: String,
        limit: Int? = null,
        after: String? = null,
        order: String? = null,
    ): List<LettaMessage> {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.get("$baseUrl/v1/agents/$agentId/messages") {
            parameter("limit", limit)
            parameter("after", after)
            parameter("order", order)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun listConversationMessages(
        conversationId: String,
        limit: Int? = null,
        after: String? = null,
        order: String? = null,
    ): List<LettaMessage> {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.get("$baseUrl/v1/conversations/$conversationId/messages") {
            parameter("limit", limit)
            parameter("after", after)
            parameter("order", order)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun resetMessages(agentId: String) {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl()

        val response = client.patch("$baseUrl/v1/agents/$agentId/reset-messages") {
            contentType(ContentType.Application.Json)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }
}
