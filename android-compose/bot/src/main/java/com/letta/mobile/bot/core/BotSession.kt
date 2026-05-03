package com.letta.mobile.bot.core

import com.letta.mobile.bot.channel.ChannelMessage
import com.letta.mobile.bot.protocol.BotStreamEvent
import com.letta.mobile.bot.channel.DeliveryResult
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Core bot session interface — Kotlin equivalent of lettabot's AgentSession.
 *
 * A BotSession manages the lifecycle of a single Letta agent instance,
 * handling message processing, conversation routing, and channel delivery.
 * Two implementations exist:
 * - [LocalBotSession]: runs on-device with Android API access
 * - [RemoteBotSession]: proxies to an external lettabot server
 */
interface BotSession {

    /**
     * Unique identifier for this *bot transport instance* (== [BotConfig.id]).
     *
     * letta-mobile-w2hx.4: this used to be the bound agent ID. It is now
     * the config ID — sessions are agent-agnostic transports, and the
     * agent travels per-message on [ChannelMessage.targetAgentId].
     */
    val configId: String

    /** Human-readable display name. */
    val displayName: String

    /** Current session status. */
    val status: StateFlow<BotStatus>

    /**
     * Send a message to the Letta agent and receive the response.
     * Maps to lettabot's `sendToAgent()`.
     *
     * @param message The incoming channel message.
     * @param conversationId Optional conversation to route to (null = use routing mode).
     * @return The agent's response as a [BotResponse].
     */
    suspend fun sendToAgent(message: ChannelMessage, conversationId: String? = null): BotResponse

    /**
     * Send a message and stream the response tokens.
     * Maps to lettabot's `streamToAgent()`.
     *
     * @param forceNew when true, transports must request a genuinely fresh
     * conversation instead of resuming any active server-side SDK session.
     */
    fun streamToAgent(
        message: ChannelMessage,
        conversationId: String? = null,
        forceNew: Boolean = false,
    ): Flow<BotResponseChunk>

    /**
     * Deliver the agent's response back to the originating channel.
     * Maps to lettabot's `deliverToChannel()`.
     */
    suspend fun deliverToChannel(response: BotResponse, sourceMessage: ChannelMessage): DeliveryResult

    /**
     * Resolve which conversation a message should be routed to,
     * based on the configured [ConversationMode].
     */
    suspend fun resolveConversation(message: ChannelMessage): String

    /** Start the bot session (connect, initialize, etc.) */
    suspend fun start()

    /** Stop the bot session and release resources. */
    suspend fun stop()
}

/** Status of a bot session. */
enum class BotStatus {
    IDLE,
    STARTING,
    RUNNING,
    PROCESSING,
    STOPPING,
    STOPPED,
    ERROR,
}

/**
 * How conversations are routed for incoming messages.
 * Maps to lettabot's conversation mode config.
 */
enum class ConversationMode {
    /** All messages share a single conversation. */
    SHARED,
    /** One conversation per channel (e.g., one per Telegram chat). */
    PER_CHANNEL,
    /** One conversation per individual chat/user. */
    PER_CHAT,
    /** Conversations are disabled — stateless request/response. */
    DISABLED,
}

/** A complete response from the agent. */
data class BotResponse(
    val agentId: String,
    val conversationId: String?,
    val text: String,
    val directives: List<Directive> = emptyList(),
    val usage: UsageInfo? = null,
)

/** A streaming chunk from the agent. */
data class BotResponseChunk(
    val text: String? = null,
    val conversationId: String? = null,
    val isComplete: Boolean = false,
    val directive: Directive? = null,
    val event: BotStreamEvent? = null,
    val toolName: String? = null,
    val toolCallId: String? = null,
    val toolInput: JsonElement? = null,
    val isError: Boolean = false,
)

/** Usage/token statistics for a response. */
data class UsageInfo(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
)
