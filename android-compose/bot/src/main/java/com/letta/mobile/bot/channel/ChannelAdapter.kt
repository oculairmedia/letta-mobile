package com.letta.mobile.bot.channel

import kotlinx.coroutines.flow.Flow

/**
 * Interface for channel adapters — Kotlin equivalent of lettabot's channel adapters.
 *
 * A channel adapter bridges between an external messaging source (notifications,
 * SMS, in-app chat, etc.) and the bot gateway. Each adapter:
 * 1. Listens for incoming messages from its channel
 * 2. Converts them to [ChannelMessage] format
 * 3. Delivers [BotResponse] results back to the channel
 */
interface ChannelAdapter {

    /** Unique identifier for this channel type. */
    val channelId: String

    /** Human-readable name for display. */
    val displayName: String

    /** Whether this adapter is currently connected and listening. */
    val isActive: Boolean

    /** Flow of incoming messages from this channel. */
    fun incomingMessages(): Flow<ChannelMessage>

    /**
     * Deliver a response to this channel.
     * @return Result indicating success or failure with details.
     */
    suspend fun deliver(response: ChannelDelivery): DeliveryResult

    /** Start listening on this channel. */
    suspend fun start()

    /** Stop listening and release resources. */
    suspend fun stop()
}

/**
 * An incoming message from any channel.
 * Normalized representation regardless of source (SMS, notification, in-app, etc.).
 */
data class ChannelMessage(
    /** Unique message ID within the source channel. */
    val messageId: String,
    /** The channel this message came from. */
    val channelId: String,
    /** Chat/conversation identifier within the channel (e.g., phone number, chat ID). */
    val chatId: String,
    /** Sender identifier within the channel. */
    val senderId: String,
    /** Display name of the sender, if available. */
    val senderName: String? = null,
    /** Text content of the message. */
    val text: String,
    /** Optional file attachments. */
    val attachments: List<Attachment> = emptyList(),
    /** Target agent ID, if the message specifies one. */
    val targetAgentId: String? = null,
    /** Timestamp in epoch millis. */
    val timestamp: Long = System.currentTimeMillis(),
    /** Arbitrary metadata from the source channel. */
    val metadata: Map<String, String> = emptyMap(),
)

/** A file attachment on an incoming message. */
data class Attachment(
    val name: String,
    val mimeType: String,
    val uri: String,
    val sizeBytes: Long? = null,
)

/** Outgoing delivery to a channel. */
data class ChannelDelivery(
    /** The channel to deliver to. */
    val channelId: String,
    /** The chat/conversation within the channel. */
    val chatId: String,
    /** Text content to send. */
    val text: String?,
    /** Files to send. */
    val files: List<DeliveryFile> = emptyList(),
    /** Whether this is a reply to a specific message. */
    val replyToMessageId: String? = null,
)

data class DeliveryFile(
    val name: String,
    val mimeType: String,
    val uri: String,
)

/** Result of delivering a message to a channel. */
sealed interface DeliveryResult {
    data class Success(val messageId: String? = null) : DeliveryResult
    data class Failed(val error: String, val cause: Throwable? = null) : DeliveryResult
}
