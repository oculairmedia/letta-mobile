package com.letta.mobile.ui.screens.chat

import com.letta.mobile.bot.protocol.BotChatRequest
import com.letta.mobile.bot.protocol.BotStreamChunk
import com.letta.mobile.bot.protocol.InternalBotClient
import com.letta.mobile.clientmode.ClientModeController
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect

@Singleton
class ClientModeChatSender @Inject constructor(
    private val internalBotClient: InternalBotClient,
    private val clientModeController: ClientModeController,
) {
    fun streamMessage(
        screenAgentId: String,
        text: String,
        existingConversationId: String?,
        isFreshRoute: Boolean,
    ): Flow<BotStreamChunk> = flow {
        val targetAgentId = when {
            isFreshRoute -> clientModeController.restartSession(screenAgentId)
            else -> clientModeController.ensureReady(screenAgentId)
        }

        internalBotClient.streamMessage(
            BotChatRequest(
                message = text,
                channelId = "letta-mobile",
                chatId = "agent:$screenAgentId",
                senderId = "letta-mobile-user",
                agentId = targetAgentId,
                conversationId = existingConversationId,
            )
        ).collect { emit(it) }
    }
}
