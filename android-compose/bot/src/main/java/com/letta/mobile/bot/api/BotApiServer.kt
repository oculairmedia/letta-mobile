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
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respondTextWriter
import io.ktor.server.response.respond
import io.ktor.server.response.header
import io.ktor.server.routing.RoutingContext
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
    private var currentAuthToken: String? = null
    private val rateLimiter = SlidingWindowRateLimiter(
        maxRequests = DEFAULT_RATE_LIMIT_REQUESTS,
        windowMillis = DEFAULT_RATE_LIMIT_WINDOW_MILLIS,
    )

    val isRunning: Boolean get() = running

    val activePort: Int? get() = if (running) currentPort else null
    val authRequired: Boolean get() = !currentAuthToken.isNullOrBlank()

    private var currentPort: Int? = null

    fun start(port: Int = DEFAULT_PORT, authToken: String? = null) {
        val normalizedAuthToken = authToken?.trim()?.takeIf { it.isNotEmpty() }
        if (running && currentPort == port && currentAuthToken == normalizedAuthToken) return
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
                suspend fun RoutingContext.requireAuthorization(): Boolean {
                    if (isAuthorizedRequest(call, normalizedAuthToken)) return true

                    Log.w(TAG, "Rejected unauthorized API request from ${clientRateLimitKey(call)} to ${call.request.path()}")
                    call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
                    call.respond(HttpStatusCode.Unauthorized, BotErrorResponse("Unauthorized"))
                    return false
                }

                suspend fun RoutingContext.requireRateLimit(): Boolean {
                    val clientKey = clientRateLimitKey(call)
                    val decision = rateLimiter.tryAcquire(clientKey)
                    call.response.applyRateLimitHeaders(
                        remaining = decision.remaining,
                        resetAfterSeconds = decision.resetAfterSeconds,
                    )
                    if (decision.allowed) return true

                    Log.w(TAG, "Rate limited API request from $clientKey to ${call.request.path()}")
                    call.respond(
                        HttpStatusCode.TooManyRequests,
                        BotErrorResponse("Too many requests. Please wait a moment."),
                    )
                    return false
                }

                // Health check
                get("/api/v1/status") {
                    if (!requireAuthorization()) return@get
                    if (!requireRateLimit()) return@get
                    call.respond(
                        internalBotClient.getStatus().copy(
                            apiPort = activePort,
                            authRequired = authRequired,
                            rateLimitRequests = DEFAULT_RATE_LIMIT_REQUESTS,
                            rateLimitWindowSeconds = DEFAULT_RATE_LIMIT_WINDOW_MILLIS / 1000L,
                        )
                    )
                }

                // List active agents
                get("/api/v1/agents") {
                    if (!requireAuthorization()) return@get
                    if (!requireRateLimit()) return@get
                    call.respond(internalBotClient.listAgents())
                }

                post("/api/v1/chat") {
                    try {
                        if (!requireAuthorization()) return@post
                        if (!requireRateLimit()) return@post
                        val request = call.receive<BotChatRequest>()
                        call.respond(internalBotClient.sendMessage(request))
                    } catch (e: Exception) {
                        Log.e(TAG, "Chat API error", e)
                        call.respond(HttpStatusCode.InternalServerError, BotErrorResponse(e.message ?: "Unknown error"))
                    }
                }

                post("/api/v1/chat/stream") {
                    try {
                        if (!requireAuthorization()) return@post
                        if (!requireRateLimit()) return@post
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
        currentAuthToken = normalizedAuthToken
        Log.i(TAG, "Bot API server started on port $port")
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 1000, timeoutMillis = 5000)
        server = null
        running = false
        currentPort = null
        currentAuthToken = null
        Log.i(TAG, "Bot API server stopped")
    }

    companion object {
        private const val TAG = "BotApiServer"
        const val DEFAULT_PORT = 8080
        private const val DEFAULT_RATE_LIMIT_REQUESTS = 30
        private const val DEFAULT_RATE_LIMIT_WINDOW_MILLIS = 60_000L
    }
}

internal fun clientRateLimitKey(call: ApplicationCall): String {
    val forwarded = call.request.header("X-Forwarded-For")
        ?.substringBefore(',')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    return forwarded ?: call.request.local.remoteHost
}

internal fun io.ktor.server.response.ApplicationResponse.applyRateLimitHeaders(
    remaining: Int,
    resetAfterSeconds: Long,
) {
    header("X-RateLimit-Limit", "30")
    header("X-RateLimit-Remaining", remaining.coerceAtLeast(0).toString())
    header("Retry-After", resetAfterSeconds.coerceAtLeast(0).toString())
}

internal fun isAuthorizedRequest(call: ApplicationCall, authToken: String?): Boolean =
    isAuthorizedHeader(call.request.header(HttpHeaders.Authorization), authToken)

internal fun isAuthorizedHeader(authHeader: String?, authToken: String?): Boolean {
    val expectedToken = authToken?.trim()?.takeIf { it.isNotEmpty() } ?: return true
    return extractBearerToken(authHeader) == expectedToken
}

internal fun extractBearerToken(authHeader: String?): String? {
    if (authHeader.isNullOrBlank()) return null
    val prefix = "Bearer "
    return if (authHeader.startsWith(prefix, ignoreCase = true)) {
        authHeader.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
    } else {
        null
    }
}

internal class SlidingWindowRateLimiter(
    private val maxRequests: Int,
    private val windowMillis: Long,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val requestTimesByClient = mutableMapOf<String, ArrayDeque<Long>>()

    @Synchronized
    fun tryAcquire(clientKey: String): RateLimitDecision {
        val now = nowMillis()
        val windowStart = now - windowMillis
        val bucket = requestTimesByClient.getOrPut(clientKey) { ArrayDeque() }

        while (bucket.isNotEmpty() && bucket.first() <= windowStart) {
            bucket.removeFirst()
        }

        if (bucket.size >= maxRequests) {
            val oldest = bucket.firstOrNull() ?: now
            val resetAfterMillis = (oldest + windowMillis - now).coerceAtLeast(0)
            return RateLimitDecision(
                allowed = false,
                remaining = 0,
                resetAfterSeconds = ceilDiv(resetAfterMillis, 1000L),
            )
        }

        bucket.addLast(now)
        val remaining = (maxRequests - bucket.size).coerceAtLeast(0)
        return RateLimitDecision(
            allowed = true,
            remaining = remaining,
            resetAfterSeconds = ceilDiv(windowMillis, 1000L),
        )
    }
}

internal data class RateLimitDecision(
    val allowed: Boolean,
    val remaining: Int,
    val resetAfterSeconds: Long,
)

private fun ceilDiv(value: Long, divisor: Long): Long {
    if (value <= 0L) return 0L
    return ((value - 1) / divisor) + 1
}
