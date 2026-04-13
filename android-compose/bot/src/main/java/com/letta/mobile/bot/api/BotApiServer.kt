package com.letta.mobile.bot.api

import android.util.Log
import com.letta.mobile.bot.channel.ChannelMessage
import com.letta.mobile.bot.core.BotGateway
import com.letta.mobile.bot.core.BotResponse
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Embedded HTTP API server — exposes the local bot as an API endpoint.
 * Kotlin equivalent of lettabot's Express/HTTP server.
 *
 * This enables other devices/services on the local network to interact with
 * the on-device bot via HTTP, mirroring the lettabot API contract:
 * - POST /api/v1/chat — synchronous chat
 * - GET /api/v1/status — health check
 * - GET /api/v1/agents — list active agents
 *
 * The server runs on a configurable port (default 8080) and is only started
 * when the bot is in LOCAL mode with the API server enabled.
 */
@Singleton
class BotApiServer @Inject constructor(
    private val gateway: BotGateway,
) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private var running = false

    val isRunning: Boolean get() = running

    fun start(port: Int = DEFAULT_PORT) {
        if (running) return

        server = embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    prettyPrint = false
                })
            }

            routing {
                // Health check
                get("/api/v1/status") {
                    call.respond(StatusResponse(
                        status = gateway.status.value.name.lowercase(),
                        agents = gateway.sessions.value.keys.toList(),
                    ))
                }

                // List active agents
                get("/api/v1/agents") {
                    val agents = gateway.sessions.value.map { (id, session) ->
                        AgentInfo(id = id, name = session.displayName, status = session.status.value.name.lowercase())
                    }
                    call.respond(agents)
                }

                // Chat endpoint
                post("/api/v1/chat") {
                    try {
                        val request = call.receive<ChatRequest>()
                        val message = ChannelMessage(
                            messageId = UUID.randomUUID().toString(),
                            channelId = request.channelId ?: "api",
                            chatId = request.chatId ?: "api",
                            senderId = request.senderId ?: "api_user",
                            senderName = request.senderName,
                            text = request.message,
                            targetAgentId = request.agentId,
                        )

                        val response: BotResponse = gateway.routeMessage(message)
                        call.respond(ChatResponse(
                            response = response.text,
                            conversationId = response.conversationId,
                            agentId = response.agentId,
                        ))
                    } catch (e: Exception) {
                        Log.e(TAG, "Chat API error", e)
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Unknown error"))
                    }
                }
            }
        }

        server?.start(wait = false)
        running = true
        Log.i(TAG, "Bot API server started on port $port")
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 1000, timeoutMillis = 5000)
        server = null
        running = false
        Log.i(TAG, "Bot API server stopped")
    }

    companion object {
        private const val TAG = "BotApiServer"
        const val DEFAULT_PORT = 8080
    }
}

@Serializable
data class StatusResponse(
    val status: String,
    val agents: List<String>,
)

@Serializable
data class AgentInfo(
    val id: String,
    val name: String,
    val status: String,
)

@Serializable
data class ChatRequest(
    val message: String,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("chat_id") val chatId: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("sender_name") val senderName: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
)

@Serializable
data class ChatResponse(
    val response: String,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
)

@Serializable
data class ErrorResponse(
    val error: String,
)
