package com.letta.mobile.feature.chat.testutil

import com.letta.mobile.bot.protocol.BotAgentInfo
import com.letta.mobile.bot.protocol.BotChatRequest
import com.letta.mobile.bot.protocol.BotChatResponse
import com.letta.mobile.bot.protocol.BotClient
import com.letta.mobile.bot.protocol.BotStatusResponse
import com.letta.mobile.bot.protocol.BotStreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class FakeBotClient : BotClient {
    var stream: Flow<BotStreamChunk> = emptyFlow()
    val streamRequests: MutableList<BotChatRequest> = mutableListOf()

    override suspend fun sendMessage(request: BotChatRequest): BotChatResponse =
        error("FakeBotClient.sendMessage not configured")

    override fun streamMessage(request: BotChatRequest): Flow<BotStreamChunk> {
        streamRequests += request
        return stream
    }

    override suspend fun getStatus(): BotStatusResponse =
        error("FakeBotClient.getStatus not configured")

    override suspend fun listAgents(): List<BotAgentInfo> =
        error("FakeBotClient.listAgents not configured")

    override suspend fun abort() = Unit
}
