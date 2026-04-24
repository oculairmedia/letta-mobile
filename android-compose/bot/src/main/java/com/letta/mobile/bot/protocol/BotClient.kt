package com.letta.mobile.bot.protocol

import kotlinx.coroutines.flow.Flow

interface BotClient {
    suspend fun sendMessage(request: BotChatRequest): BotChatResponse

    fun streamMessage(request: BotChatRequest): Flow<BotStreamChunk>

    suspend fun getStatus(): BotStatusResponse

    suspend fun listAgents(): List<BotAgentInfo>
}

interface GatewayReadyClient {
    suspend fun ensureGatewayReady(
        agentId: String,
        conversationId: String? = null,
    )
}
