package com.letta.mobile.bot.protocol

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

class ExternalBotClient(
    private val baseUrl: String,
    token: String? = null,
) : BotClient, AutoCloseable {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 120_000
        }
        token?.let { bearerToken ->
            install(Auth) {
                bearer {
                    loadTokens { BearerTokens(bearerToken, bearerToken) }
                }
            }
        }
    }

    override suspend fun sendMessage(request: BotChatRequest): BotChatResponse {
        val response = client.post("$baseUrl/api/v1/chat") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (response.status.value !in 200..299) {
            throw RuntimeException("Bot server error: ${response.status.value} ${response.bodyAsText()}")
        }
        return response.body()
    }

    override fun streamMessage(request: BotChatRequest): Flow<BotStreamChunk> = flow {
        val response = client.preparePost("$baseUrl/api/v1/chat/stream") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.execute()

        when {
            response.status.value == 404 || response.status.value == 405 -> {
                val chatResponse = sendMessage(request)
                emit(
                    BotStreamChunk(
                        text = chatResponse.response,
                        conversationId = chatResponse.conversationId,
                        agentId = chatResponse.agentId,
                        done = true,
                    )
                )
            }

            response.status.value !in 200..299 -> {
                throw RuntimeException("Bot server stream error: ${response.status.value} ${response.bodyAsText()}")
            }

            else -> {
                BotSseParser.parse(response.bodyAsChannel()).collect { emit(it) }
            }
        }
    }

    override suspend fun getStatus(): BotStatusResponse {
        val response = client.get("$baseUrl/api/v1/status")
        if (response.status.value !in 200..299) {
            throw RuntimeException("Bot server error: ${response.status.value} ${response.bodyAsText()}")
        }
        return BotStatusResponseParser.parse(json, json.parseToJsonElement(response.bodyAsText()))
    }

    override suspend fun listAgents(): List<BotAgentInfo> {
        val response = client.get("$baseUrl/api/v1/agents")
        if (response.status.value !in 200..299) {
            throw RuntimeException("Bot server error: ${response.status.value} ${response.bodyAsText()}")
        }
        return response.body()
    }

    override fun close() {
        client.close()
    }
}
