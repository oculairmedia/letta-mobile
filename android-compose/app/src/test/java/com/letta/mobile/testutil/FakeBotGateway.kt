package com.letta.mobile.testutil

import com.letta.mobile.bot.channel.ChannelMessage
import com.letta.mobile.bot.channel.DeliveryResult
import com.letta.mobile.bot.config.BotConfig
import com.letta.mobile.bot.core.BotResponse
import com.letta.mobile.bot.core.BotResponseChunk
import com.letta.mobile.bot.core.BotSession
import com.letta.mobile.bot.core.GatewayStatus
import com.letta.mobile.bot.core.IBotGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

class FakeBotGateway(
    status: GatewayStatus = GatewayStatus.STOPPED,
    sessions: Map<String, BotSession> = emptyMap(),
) : IBotGateway {
    override val sessions = MutableStateFlow(sessions)
    override val status = MutableStateFlow(status)
    val startedConfigs: MutableList<List<BotConfig>> = mutableListOf()
    var stopCount: Int = 0

    override suspend fun start(configs: List<BotConfig>) {
        startedConfigs += configs
        status.value = GatewayStatus.RUNNING
    }

    override suspend fun stop() {
        stopCount += 1
        sessions.value = emptyMap()
        status.value = GatewayStatus.STOPPED
    }

    override fun getSession(configId: String): BotSession? = sessions.value[configId]

    override fun getDefaultSession(): BotSession? = sessions.value.values.firstOrNull()

    override suspend fun routeMessage(message: ChannelMessage, conversationId: String?): BotResponse =
        error("FakeBotGateway.routeMessage not configured")

    override fun streamMessage(
        message: ChannelMessage,
        conversationId: String?,
        forceNew: Boolean,
    ): Flow<BotResponseChunk> = emptyFlow()

    override suspend fun routeAndDeliver(message: ChannelMessage): DeliveryResult =
        error("FakeBotGateway.routeAndDeliver not configured")

    override suspend fun abortStream() = Unit
}
