package com.letta.mobile.bot.core

import android.util.Log
import com.letta.mobile.bot.channel.ChannelDelivery
import com.letta.mobile.bot.channel.ChannelMessage
import com.letta.mobile.bot.channel.DeliveryResult
import com.letta.mobile.bot.config.BotConfig
import com.letta.mobile.bot.message.DirectiveParser
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Remote bot session — proxies requests to an external lettabot HTTP server.
 *
 * This implementation connects to a running lettabot instance (TypeScript/Python)
 * via its HTTP API endpoints:
 * - POST /api/v1/chat — synchronous message
 * - POST /api/v1/chat/async — async message
 * - GET /api/v1/status — health check
 *
 * The external server handles all agent orchestration, conversation routing,
 * and channel management. This session is a thin HTTP client.
 */
class RemoteBotSession @AssistedInject constructor(
    @Assisted private val config: BotConfig,
) : BotSession {

    override val agentId: String = config.agentId
    override val displayName: String = config.displayName

    private val _status = MutableStateFlow(BotStatus.IDLE)
    override val status = _status.asStateFlow()

    private var client: HttpClient? = null

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    override suspend fun start() {
        _status.value = BotStatus.STARTING
        val baseUrl = config.remoteUrl ?: throw IllegalStateException("Remote URL not configured")

        client = HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 120_000
            }
            config.remoteToken?.let { token ->
                install(Auth) {
                    bearer { loadTokens { BearerTokens(token, token) } }
                }
            }
        }

        // Verify connection
        try {
            val response = client!!.get("$baseUrl/api/v1/status")
            if (response.status.value !in 200..299) {
                throw IllegalStateException("Bot server returned ${response.status.value}")
            }
            _status.value = BotStatus.RUNNING
            Log.i(TAG, "Connected to remote bot at $baseUrl")
        } catch (e: Exception) {
            _status.value = BotStatus.ERROR
            Log.e(TAG, "Failed to connect to remote bot at $baseUrl", e)
            throw e
        }
    }

    override suspend fun stop() {
        _status.value = BotStatus.STOPPING
        client?.close()
        client = null
        _status.value = BotStatus.STOPPED
    }

    override suspend fun sendToAgent(message: ChannelMessage, conversationId: String?): BotResponse {
        val httpClient = client ?: throw IllegalStateException("Session not started")
        val baseUrl = config.remoteUrl!!

        _status.value = BotStatus.PROCESSING
        try {
            val request = RemoteChatRequest(
                message = message.text,
                channelId = message.channelId,
                chatId = message.chatId,
                senderId = message.senderId,
                senderName = message.senderName,
                conversationId = conversationId,
            )

            val response = httpClient.post("$baseUrl/api/v1/chat") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            if (response.status.value !in 200..299) {
                throw RuntimeException("Bot server error: ${response.status.value} ${response.bodyAsText()}")
            }

            val chatResponse: RemoteChatResponse = response.body()
            val parseResult = if (config.directivesEnabled) {
                DirectiveParser.parse(chatResponse.response)
            } else {
                DirectiveParser.ParseResult(chatResponse.response, emptyList())
            }

            _status.value = BotStatus.RUNNING
            return BotResponse(
                agentId = agentId,
                conversationId = chatResponse.conversationId,
                text = parseResult.cleanText,
                directives = parseResult.directives,
            )
        } catch (e: Exception) {
            _status.value = BotStatus.RUNNING
            throw e
        }
    }

    override fun streamToAgent(message: ChannelMessage, conversationId: String?): Flow<BotResponseChunk> = flow {
        // For remote mode, fall back to non-streaming and emit the full response.
        // Streaming support can be added later with SSE.
        val response = sendToAgent(message, conversationId)
        emit(BotResponseChunk(text = response.text, isComplete = true))
    }

    override suspend fun deliverToChannel(response: BotResponse, sourceMessage: ChannelMessage): DeliveryResult {
        // In remote mode, the bot server handles delivery.
        // This is a no-op — the response is returned to the caller (gateway/channel adapter).
        return DeliveryResult.Success()
    }

    override suspend fun resolveConversation(message: ChannelMessage): String {
        // In remote mode, the bot server handles conversation routing.
        // Return a synthetic ID based on the routing mode.
        return when (config.conversationMode) {
            ConversationMode.SHARED -> config.sharedConversationId ?: "shared"
            ConversationMode.PER_CHANNEL -> "channel:${message.channelId}"
            ConversationMode.PER_CHAT -> "chat:${message.channelId}:${message.chatId}"
            ConversationMode.DISABLED -> "stateless"
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(config: BotConfig): RemoteBotSession
    }

    companion object {
        private const val TAG = "RemoteBotSession"
    }
}

/** Request payload for the remote bot's /api/v1/chat endpoint. */
@Serializable
internal data class RemoteChatRequest(
    val message: String,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("chat_id") val chatId: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("sender_name") val senderName: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
)

/** Response from the remote bot's /api/v1/chat endpoint. */
@Serializable
internal data class RemoteChatResponse(
    val response: String,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
)
