package com.letta.mobile.cli.runtime

import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationCreateParams
import com.letta.mobile.data.model.LettaMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.call.body
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json

internal val CliJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}

internal fun newCliOtid(): String = "cm-cli-${UUID.randomUUID()}"

internal fun nowIso(): String = Instant.now().toString()

internal fun cliHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) {
        json(CliJson)
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 10 * 60 * 1000
        connectTimeoutMillis = 30 * 1000
        socketTimeoutMillis = 5 * 60 * 1000
    }
}

internal class CliRestClient(
    private val baseUrl: String,
    private val token: String,
    private val client: HttpClient = cliHttpClient(),
) {
    suspend fun createConversation(agentId: String): Conversation {
        val response = client.post("${baseUrl.trimEnd('/')}/v1/conversations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            parameter("agent_id", agentId)
            setBody(ConversationCreateParams(agentId = agentId))
        }
        if (response.status.value !in 200..299) {
            throw IllegalStateException("create conversation failed: HTTP ${response.status.value}: ${response.bodyAsText()}")
        }
        return response.body()
    }

    suspend fun fetchMessages(
        conversationId: String,
        limit: Int,
    ): List<LettaMessage> {
        val response = client.get("${baseUrl.trimEnd('/')}/v1/conversations/$conversationId/messages") {
            bearerAuth(token)
            parameter("limit", limit)
            parameter("order", "asc")
        }
        if (response.status.value !in 200..299) {
            throw IllegalStateException("fetch messages failed: HTTP ${response.status.value}: ${response.bodyAsText()}")
        }
        return response.body()
    }

    fun close() {
        client.close()
    }
}
