package com.letta.mobile.bot.api

import android.util.Log
import com.letta.mobile.bot.protocol.BotAgentInfo
import com.letta.mobile.bot.protocol.BotChatRequest
import com.letta.mobile.bot.protocol.BotChatResponse
import com.letta.mobile.bot.protocol.BotErrorResponse
import com.letta.mobile.bot.protocol.BotStatusResponse
import com.letta.mobile.bot.protocol.BotStreamChunk
import com.letta.mobile.bot.protocol.InternalBotClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respondTextWriter
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
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
    private val internalBotClient: InternalBotClient,
) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private var running = false

    val isRunning: Boolean get() = running

    val activePort: Int? get() = if (running) currentPort else null

    private var currentPort: Int? = null

    fun start(port: Int = DEFAULT_PORT) {
        if (running && currentPort == port) return
        if (running) {
            stop()
        }

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
                    call.respond(internalBotClient.getStatus())
                }

                // List active agents
                get("/api/v1/agents") {
                    call.respond(internalBotClient.listAgents())
                }

                post("/api/v1/chat") {
                    try {
                        val request = call.receive<BotChatRequest>()
                        call.respond(internalBotClient.sendMessage(request))
                    } catch (e: Exception) {
                        Log.e(TAG, "Chat API error", e)
                        call.respond(HttpStatusCode.InternalServerError, BotErrorResponse(e.message ?: "Unknown error"))
                    }
                }

                post("/api/v1/chat/stream") {
                    try {
                        val request = call.receive<BotChatRequest>()
                        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                            internalBotClient.streamMessage(request).collect { chunk ->
                                write("data: ${Json.encodeToString(chunk)}\n\n")
                                flush()
                                if (chunk.done) {
                                    write("data: [DONE]\n\n")
                                    flush()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Chat stream API error", e)
                        call.respond(HttpStatusCode.InternalServerError, BotErrorResponse(e.message ?: "Unknown error"))
                    }
                }
            }
        }

        server?.start(wait = false)
        running = true
        currentPort = port
        Log.i(TAG, "Bot API server started on port $port")
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 1000, timeoutMillis = 5000)
        server = null
        running = false
        currentPort = null
        Log.i(TAG, "Bot API server stopped")
    }

    companion object {
        private const val TAG = "BotApiServer"
        const val DEFAULT_PORT = 8080
    }
}
