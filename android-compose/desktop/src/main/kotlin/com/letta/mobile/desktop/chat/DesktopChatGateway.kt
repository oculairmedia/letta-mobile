package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.stream.SseFrame
import com.letta.mobile.data.stream.SseParser
import com.letta.mobile.data.timeline.TimelineNoActiveRunException
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.TimelineTransportHttpException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject

typealias DesktopChatGateway = ChatGateway

class DesktopLettaHttpChatGateway(
    private val config: LettaConfig,
    private val httpClient: HttpClient = createDesktopLettaHttpClient(),
) : ChatGateway, AutoCloseable {
    private val baseUrl = config.serverUrl.trimEnd('/')

    override suspend fun listConversations(limit: Int): List<Conversation> {
        val response = httpClient.get("$baseUrl/v1/conversations") {
            applyDesktopAuth()
            parameter("limit", limit)
            parameter("order", "desc")
            parameter("order_by", "last_message_at")
        }
        response.requireSuccess()
        return response.body()
    }

    override suspend fun getConversation(conversationId: String): Conversation {
        val response = httpClient.get("$baseUrl/v1/conversations/$conversationId") {
            applyDesktopAuth()
        }
        response.requireSuccess()
        return response.body()
    }

    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> {
        val response = httpClient.post("$baseUrl/v1/conversations/$conversationId/messages") {
            applyDesktopAuth()
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
            applyDesktopAuth()
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
            applyDesktopAuth()
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
            applyDesktopAuth()
            parameter("limit", limit)
            parameter("order", order)
            parameter("conversation_id", conversationId)
        }
        response.requireSuccess()
        return response.body()
    }

    override suspend fun deleteConversation(conversationId: String) {
        val response = httpClient.delete("$baseUrl/v1/conversations/$conversationId") {
            applyDesktopAuth()
        }
        response.requireSuccess()
    }

    override fun close() {
        httpClient.close()
    }

    private fun HttpRequestBuilder.applyDesktopAuth() {
        config.accessToken
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::bearerAuth)
    }
}

fun createDesktopLettaHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(desktopChatJson)
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 60_000
        socketTimeoutMillis = 60_000
    }
}

internal val desktopChatJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
}

private suspend fun HttpResponse.requireSuccess() {
    if (status.value !in 200..299) {
        throw TimelineTransportHttpException(
            code = status.value,
            message = bodyAsText(),
        )
    }
}

internal fun SseFrame.toTimelineStreamFrame(): TimelineStreamFrame = when (this) {
    SseFrame.Heartbeat -> TimelineStreamFrame.Heartbeat
    SseFrame.Done -> TimelineStreamFrame.Done
    is SseFrame.Message -> TimelineStreamFrame.Message(message)
    is SseFrame.RawEvent -> TimelineStreamFrame.RawEvent(
        event = event,
        data = data,
        id = id,
    )
}
