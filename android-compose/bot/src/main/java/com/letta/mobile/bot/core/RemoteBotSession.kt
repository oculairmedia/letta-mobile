package com.letta.mobile.bot.core

import android.util.Log
import com.letta.mobile.bot.channel.ChannelDelivery
import com.letta.mobile.bot.channel.ChannelMessage
import com.letta.mobile.bot.channel.DeliveryResult
import com.letta.mobile.bot.config.BotConfig
import com.letta.mobile.bot.config.BotServerProfileResolver
import com.letta.mobile.bot.config.ResolvedRemoteProfile
import com.letta.mobile.bot.message.DirectiveParser
import com.letta.mobile.bot.protocol.BotChatRequest
import com.letta.mobile.bot.protocol.BotClient
import com.letta.mobile.bot.protocol.ExternalBotClient
import com.letta.mobile.bot.protocol.WsBotClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

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
    private val profileResolver: BotServerProfileResolver,
) : BotSession {

    override val agentId: String = config.agentId
    override val displayName: String = config.displayName

    private val _status = MutableStateFlow(BotStatus.IDLE)
    override val status = _status.asStateFlow()

    private var client: BotClient? = null
    private var clientFactoryOverride: ((ResolvedRemoteProfile) -> BotClient)? = null

    internal val currentClient: BotClient?
        get() = client

    internal constructor(
        config: BotConfig,
        profileResolver: BotServerProfileResolver,
        clientFactoryOverride: (ResolvedRemoteProfile) -> BotClient,
    ) : this(config, profileResolver) {
        this.clientFactoryOverride = clientFactoryOverride
    }

    override suspend fun start() {
        _status.value = BotStatus.STARTING
        val resolvedProfile = profileResolver.resolve(config)
            ?: throw IllegalStateException("Remote profile is not configured")
        val baseUrl = resolvedProfile.baseUrl

        client = clientFactoryOverride?.invoke(resolvedProfile) ?: when (resolvedProfile.transport) {
            BotConfig.Transport.HTTP -> ExternalBotClient(
                baseUrl = resolvedProfile.baseUrl,
                token = resolvedProfile.authToken,
            )

            BotConfig.Transport.WS -> WsBotClient(
                baseUrl = resolvedProfile.baseUrl,
                apiKey = resolvedProfile.authToken,
            )
        }

        try {
            client!!.getStatus()
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
        (client as? AutoCloseable)?.close()
        client = null
        _status.value = BotStatus.STOPPED
    }

    override suspend fun sendToAgent(message: ChannelMessage, conversationId: String?): BotResponse {
        val remoteClient = client ?: throw IllegalStateException("Session not started")

        _status.value = BotStatus.PROCESSING
        try {
            val request = BotChatRequest(
                message = message.text,
                channelId = message.channelId,
                chatId = message.chatId,
                senderId = message.senderId,
                senderName = message.senderName,
                conversationId = conversationId,
            )

            val chatResponse = remoteClient.sendMessage(request)
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
        val remoteClient = client ?: throw IllegalStateException("Session not started")

        _status.value = BotStatus.PROCESSING
        try {
            val request = BotChatRequest(
                message = message.text,
                channelId = message.channelId,
                chatId = message.chatId,
                senderId = message.senderId,
                senderName = message.senderName,
                conversationId = conversationId,
                agentId = agentId,
            )

            val accumulated = StringBuilder()
            var latestConversationId = conversationId

            remoteClient.streamMessage(request).collect { chunk ->
                if (!chunk.done) {
                    chunk.text?.let { text ->
                        accumulated.append(text)
                        emit(
                            BotResponseChunk(
                                text = text,
                                conversationId = chunk.conversationId ?: latestConversationId,
                            )
                        )
                    }
                    latestConversationId = chunk.conversationId ?: latestConversationId
                    return@collect
                }

                latestConversationId = chunk.conversationId ?: latestConversationId
                val parseResult = if (config.directivesEnabled) {
                    DirectiveParser.parse(accumulated.toString())
                } else {
                    DirectiveParser.ParseResult(accumulated.toString(), emptyList())
                }

                emit(
                    BotResponseChunk(
                        text = parseResult.cleanText.takeIf { it.isNotBlank() },
                        conversationId = latestConversationId,
                        isComplete = true,
                        directive = parseResult.directives.firstOrNull(),
                    )
                )
            }

            _status.value = BotStatus.RUNNING
        } catch (e: Exception) {
            _status.value = BotStatus.RUNNING
            Log.e(TAG, "Error streaming message for agent $agentId", e)
            throw e
        }
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
