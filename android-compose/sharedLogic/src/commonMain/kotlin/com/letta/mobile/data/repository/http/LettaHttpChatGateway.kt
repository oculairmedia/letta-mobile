package com.letta.mobile.data.repository.http

import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationCreateParams
import com.letta.mobile.data.model.ConversationUpdateParams
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.stream.SseFrame
import com.letta.mobile.data.stream.SseParser
import com.letta.mobile.data.timeline.TimelineNoActiveRunException
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.TimelineTransportHttpException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject

/**
 * Platform-neutral Ktor implementation of the chat [ChatGateway] (conversations,
 * messages, send/stream over the Letta REST API). The HTTP request/response
 * logic is multiplatform; the platform supplies only the engine-configured
 * [HttpClient] (e.g. desktop's CIO client with content-negotiation + timeouts).
 *
 * Extracted from the desktop gateway so desktop — and eventually Android —
 * share one implementation instead of two drifting Ktor clients
 * (letta-mobile-mqzkc).
 */
open class LettaHttpChatGateway(
    private val config: LettaConfig,
    private val httpClient: HttpClient,
) : ChatGateway, AutoCloseable {
    private val baseUrl = config.serverUrl.trimEnd('/')

    override suspend fun listConversations(limit: Int, archiveStatus: String?): List<Conversation> {
        val response = httpClient.get("$baseUrl/v1/conversations") {
            applyAuth()
            parameter("limit", limit)
            parameter("order", "desc")
            parameter("order_by", "last_message_at")
            archiveStatus?.let { parameter("archive_status", it) }
        }
        response.requireSuccess()
        return response.body()
    }

    override suspend fun getConversation(conversationId: String): Conversation {
        val response = httpClient.get("$baseUrl/v1/conversations/$conversationId") {
            applyAuth()
        }
        response.requireSuccess()
        return response.body()
    }

    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> {
        val response = httpClient.post("$baseUrl/v1/conversations/$conversationId/messages") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(request)
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            }
        }
        response.requireSuccess()
        return SseParser.parse(response.bodyAsChannel())
    }

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> {
        val response = httpClient.post("$baseUrl/v1/conversations/$conversationId/stream") {
            applyAuth()
            contentType(ContentType.Application.Json)
            accept(ContentType.Text.EventStream)
            header(HttpHeaders.Accept, "text/event-stream")
            setBody(buildJsonObject {})
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            }
        }

        if (response.status == HttpStatusCode.BadRequest || response.status == HttpStatusCode.NotFound) {
            val body = response.bodyAsText()
            val isIdle = body.contains("No active runs", ignoreCase = true) ||
                body.contains("EXPIRED:", ignoreCase = true) ||
                body.contains("is now expired", ignoreCase = true)
            if (isIdle) {
                throw TimelineNoActiveRunException(conversationId)
            }
            throw TimelineTransportHttpException(response.status.value, body)
        }

        response.requireSuccess()
        return SseParser.parseFrames(response.bodyAsChannel())
            .map { it.toTimelineStreamFrame() }
    }

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> {
        val response = httpClient.get("$baseUrl/v1/conversations/$conversationId/messages") {
            applyAuth()
            parameter("limit", limit)
            parameter("after", after)
            parameter("order", order)
        }
        response.requireSuccess()
        return response.body()
    }

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> {
        val response = httpClient.get("$baseUrl/v1/agents/$agentId/messages") {
            applyAuth()
            parameter("limit", limit)
            parameter("order", order)
            parameter("conversation_id", conversationId)
        }
        response.requireSuccess()
        return response.body()
    }

    override suspend fun deleteConversation(conversationId: String) {
        val response = httpClient.delete("$baseUrl/v1/conversations/$conversationId") {
            applyAuth()
        }
        response.requireSuccess()
    }

    /** Create a new conversation for [agentId]; returns the created conversation. */
    suspend fun createConversation(agentId: String, summary: String? = null): Conversation {
        val response = httpClient.post("$baseUrl/v1/conversations") {
            applyAuth()
            contentType(ContentType.Application.Json)
            parameter("agent_id", agentId)
            setBody(
                ConversationCreateParams(
                    agentId = AgentId(agentId),
                    summary = summary,
                ),
            )
        }
        response.requireSuccess()
        return response.body()
    }

    /** Create a new agent and return it. */
    suspend fun createAgent(params: AgentCreateParams): Agent {
        val response = httpClient.post("$baseUrl/v1/agents") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        response.requireSuccess()
        return response.body()
    }

    /** List the LLM models available on this backend (for the model picker). */
    suspend fun listLlmModels(): List<LlmModel> {
        val response = httpClient.get("$baseUrl/v1/models") {
            applyAuth()
        }
        response.requireSuccess()
        return response.body()
    }

    /** Set the model override for an existing conversation. */
    suspend fun setConversationModel(conversationId: String, model: String): Conversation {
        val response = httpClient.patch("$baseUrl/v1/conversations/$conversationId") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(ConversationUpdateParams(model = model))
        }
        response.requireSuccess()
        return response.body()
    }

    /** Archive or restore (un-archive) an existing conversation — non-destructive. */
    suspend fun setConversationArchived(conversationId: String, archived: Boolean): Conversation {
        val response = httpClient.patch("$baseUrl/v1/conversations/$conversationId") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(ConversationUpdateParams(archived = archived))
        }
        response.requireSuccess()
        return response.body()
    }

    override fun close() {
        httpClient.close()
    }

    private fun HttpRequestBuilder.applyAuth() {
        config.accessToken
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::bearerAuth)
    }
}

private suspend fun HttpResponse.requireSuccess() {
    if (status.value !in 200..299) {
        throw TimelineTransportHttpException(
            code = status.value,
            message = bodyAsText(),
        )
    }
}

private fun SseFrame.toTimelineStreamFrame(): TimelineStreamFrame = when (this) {
    SseFrame.Heartbeat -> TimelineStreamFrame.Heartbeat
    SseFrame.Done -> TimelineStreamFrame.Done
    is SseFrame.Message -> TimelineStreamFrame.Message(message)
    is SseFrame.RawEvent -> TimelineStreamFrame.RawEvent(
        event = event,
        data = data,
        id = id,
    )
}
