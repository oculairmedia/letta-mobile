package com.letta.mobile.bot.core

import com.letta.mobile.bot.channel.ChannelMessage
import com.letta.mobile.bot.channel.DeliveryResult
import com.letta.mobile.bot.config.BotConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface IBotGateway {
    val sessions: StateFlow<Map<String, BotSession>>
    val status: StateFlow<GatewayStatus>

    suspend fun start(configs: List<BotConfig>)
    suspend fun stop()
    fun getSession(configId: String): BotSession?
    fun getDefaultSession(): BotSession?
    suspend fun routeMessage(message: ChannelMessage, conversationId: String? = null): BotResponse
    fun streamMessage(
        message: ChannelMessage,
        conversationId: String? = null,
        forceNew: Boolean = false,
    ): Flow<BotResponseChunk>
    suspend fun routeAndDeliver(message: ChannelMessage): DeliveryResult
    suspend fun abortStream()
}
