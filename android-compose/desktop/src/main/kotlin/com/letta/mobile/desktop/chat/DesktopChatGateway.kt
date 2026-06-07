package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.stream.SseFrame
import com.letta.mobile.data.timeline.TimelineNoActiveRunException
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.TimelineTransport
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
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject

interface DesktopChatGateway : TimelineTransport {
    suspend fun listConversations(limit: Int = 40): List<Conversation>
    suspend fun getConversation(conversationId: String): Conversation
}

class DesktopLettaHttpChatGateway(
    private val config: LettaConfig,
    private val httpClient: HttpClient = createDesktopLettaHttpClient(),
) : DesktopChatGateway, AutoCloseable {
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
        return DesktopSseParser.parse(response.bodyAsChannel())
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
        return DesktopSseParser.parseFrames(response.bodyAsChannel())
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

    suspend fun deleteConversation(conversationId: String) {
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

internal object DesktopSseParser {
    private data class ProcessedEvent(
        val frame: SseFrame? = null,
        val isDone: Boolean = false,
    )

    fun parse(channel: ByteReadChannel): Flow<LettaMessage> =
        parseFrames(channel)
            .filterIsInstance<SseFrame.Message>()
            .map { it.message }

    fun parseFrames(channel: ByteReadChannel): Flow<SseFrame> = flow {
        val buffer = StringBuilder()
        val lineReader = DesktopUtf8LineReader(channel)
        var isDone = false

        while (!isDone) {
            val line = lineReader.readLine() ?: break

            if (line.isEmpty()) {
                val event = buffer.toString()
                buffer.clear()

                if (event.isNotBlank()) {
                    val processed = processEvent(event)
                    if (processed.isDone) {
                        isDone = true
                    } else {
                        processed.frame?.let { emit(it) }
                    }
                }
            } else {
                buffer.append(line).append('\n')
            }
        }

        if (!isDone && buffer.isNotBlank()) {
            val processed = processEvent(buffer.toString())
            if (!processed.isDone) {
                processed.frame?.let { emit(it) }
            }
        }
    }

    private fun processEvent(event: String): ProcessedEvent {
        val lines = event.lineSequence()
            .filter { it.isNotEmpty() }
            .toList()
        val hasComment = lines.any { it.startsWith(":") }
        val dataLines = lines
            .filter { it.startsWith("data:") }
            .map { line ->
                line.removePrefix("data:").let { data ->
                    if (data.startsWith(" ")) data.drop(1) else data
                }
            }

        if (dataLines.isEmpty()) {
            return if (hasComment) {
                ProcessedEvent(frame = SseFrame.Heartbeat)
            } else {
                ProcessedEvent()
            }
        }

        val data = dataLines.joinToString("\n").trim()

        if (data == "[DONE]") {
            return ProcessedEvent(frame = SseFrame.Done, isDone = true)
        }

        if (!data.startsWith("{") || !data.contains("\"message_type\"")) {
            return ProcessedEvent(frame = SseFrame.Heartbeat)
        }

        return runCatching {
            val message = desktopChatJson.decodeFromString<LettaMessage>(data)
            if (message.messageType == "ping") {
                ProcessedEvent(frame = SseFrame.Heartbeat)
            } else {
                ProcessedEvent(frame = SseFrame.Message(message))
            }
        }.getOrDefault(ProcessedEvent())
    }
}

private class DesktopUtf8LineReader(
    private val channel: ByteReadChannel,
    private val chunkSize: Int = 1024,
) {
    private val pending = StringBuilder()
    private val buffer = ByteArray(chunkSize)

    suspend fun readLine(): String? {
        while (true) {
            val newlineIndex = pending.indexOf("\n")
            if (newlineIndex >= 0) {
                val line = pending.substring(0, newlineIndex).removeSuffix("\r")
                pending.delete(0, newlineIndex + 1)
                return line
            }

            val read = channel.readAvailable(buffer, 0, buffer.size)
            when {
                read > 0 -> pending.append(buffer.decodeToString(endIndex = read))
                read == -1 -> {
                    if (pending.isEmpty()) return null
                    val line = pending.toString().removeSuffix("\r")
                    pending.clear()
                    return line
                }
            }
        }
    }
}
