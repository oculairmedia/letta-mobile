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
import com.letta.mobile.bot.protocol.GatewayReadyClient
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

    override val configId: String = config.id
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
                // letta-mobile-flk.2: opt into the gateway's progressive
                // tool-call mode so the dedup-by-id renderer can show
                // running snapshots before the terminal completed frame.
                progressiveToolCalls = true,
            )
        }

        try {
            client!!.getStatus()
            // letta-mobile-w2hx.4: was ensureGatewayReady(agentId) here.
            // Sessions are now agent-agnostic transports — the per-agent
            // WS pool (w2hx.3) opens sessions lazily on first message, so
            // at start time we just confirm the HTTP layer is reachable.
            _status.value = BotStatus.RUNNING
            Log.i(TAG, "Connected to remote bot at $baseUrl (config $configId)")
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
        val agentId = requireAgent(message)

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

    override fun streamToAgent(
        message: ChannelMessage,
        conversationId: String?,
    ): Flow<BotResponseChunk> = flow {
        val remoteClient = client ?: throw IllegalStateException("Session not started")
        val agentId = requireAgent(message)

        _status.value = BotStatus.PROCESSING
        try {
            // letta-mobile-w2hx.7: a null `conversationId` here means
            // "the chat row has no conv yet" → the gateway creates a
            // fresh Letta conversation and echoes the new id back on
            // the first chunk. There is no longer a force_new flag.
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
                    chunk.text?.let { text -> accumulated.append(text) }
                    emit(
                        BotResponseChunk(
                            text = chunk.text,
                            conversationId = chunk.conversationId ?: latestConversationId,
                            event = chunk.event,
                            toolName = chunk.toolName,
                            toolCallId = chunk.toolCallId,
                            toolInput = chunk.toolInput,
                            isError = chunk.isError,
                        )
                    )
                    latestConversationId = chunk.conversationId ?: latestConversationId
                    return@collect
                }

                latestConversationId = chunk.conversationId ?: latestConversationId
                // letta-mobile-uww.12: terminal-frame contract under PURE-DELTA
                // gateway streaming (lettabot ws-gateway after PARTIAL_JSON +
                // delta migration). The previous code unconditionally
                // re-emitted the full accumulated text on the `done` frame —
                // this was correct when the gateway sent cumulative
                // SNAPSHOTS (the consumer would replace each chunk), but is
                // now a duplication bug: delta-appending consumers (Client
                // Mode timeline-path ASSISTANT branch in AdminChatViewModel)
                // append the accumulated string AGAIN, producing the
                // doubled-bubble field repro (assistant reply rendered
                // twice contiguously, no separator).
                //
                // New contract:
                //   - Default: terminal frame carries ONLY `isComplete`,
                //     `conversationId`, and `directive`. The caller has
                //     already received every byte via per-chunk `text`
                //     deltas; the text MUST NOT be re-emitted.
                //   - Directive carve-out: when DirectiveParser strips
                //     directive markers from the accumulated text (e.g.
                //     `<no-reply/>`), the cleaned text DIFFERS from what
                //     the consumer accumulated, so we emit it as a final
                //     replacement payload. Consumers treating this as a
                //     final-snapshot replacement (the existing semantics
                //     under `replaceAssistant`/`isComplete`) handle this
                //     correctly without duplication.
                val parseResult = if (config.directivesEnabled) {
                    DirectiveParser.parse(accumulated.toString())
                } else {
                    DirectiveParser.ParseResult(accumulated.toString(), emptyList())
                }
                val accumulatedText = accumulated.toString()
                val terminalText = if (parseResult.cleanText != accumulatedText) {
                    parseResult.cleanText.takeIf { it.isNotBlank() }
                } else {
                    null
                }

                emit(
                    BotResponseChunk(
                        text = terminalText,
                        conversationId = latestConversationId,
                        isComplete = true,
                        directive = parseResult.directives.firstOrNull(),
                    )
                )
            }

            _status.value = BotStatus.RUNNING
        } catch (e: Exception) {
            _status.value = BotStatus.RUNNING
            Log.e(TAG, "Error streaming message for agent $agentId on config $configId", e)
            throw e
        }
    }

    private fun requireAgent(message: ChannelMessage): String =
        message.targetAgentId
            ?: error("ChannelMessage(messageId=${message.messageId}) has no targetAgentId; " +
                "the bound-agent concept was removed in w2hx.4 — callers must populate it.")

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
