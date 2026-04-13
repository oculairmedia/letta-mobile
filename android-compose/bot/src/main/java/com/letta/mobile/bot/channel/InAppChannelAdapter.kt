package com.letta.mobile.bot.channel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-app channel adapter — bridges the existing chat UI to the bot gateway.
 *
 * This adapter allows the app's chat screen to act as a bot channel,
 * enabling the user to interact with the local bot directly through
 * the same UI they use for remote Letta conversations.
 */
@Singleton
class InAppChannelAdapter @Inject constructor() : ChannelAdapter {

    override val channelId: String = CHANNEL_ID
    override val displayName: String = "In-App Chat"
    private var _active: Boolean = false
    override val isActive: Boolean get() = _active

    private val _incoming = MutableSharedFlow<ChannelMessage>(extraBufferCapacity = 64)
    private val _responses = MutableSharedFlow<ChannelDelivery>(extraBufferCapacity = 64)

    override fun incomingMessages(): Flow<ChannelMessage> = _incoming.asSharedFlow()

    /** Flow of outgoing responses — the chat UI subscribes to this. */
    fun outgoingResponses(): Flow<ChannelDelivery> = _responses.asSharedFlow()

    /**
     * Called by the chat UI to inject a user message into the bot pipeline.
     */
    suspend fun sendFromUI(message: ChannelMessage) {
        _incoming.emit(message)
    }

    override suspend fun deliver(response: ChannelDelivery): DeliveryResult {
        return try {
            _responses.emit(response)
            DeliveryResult.Success()
        } catch (e: Exception) {
            DeliveryResult.Failed("Failed to deliver to in-app channel", e)
        }
    }

    override suspend fun start() {
        _active = true
    }

    override suspend fun stop() {
        _active = false
    }

    companion object {
        const val CHANNEL_ID = "in_app"
    }
}
