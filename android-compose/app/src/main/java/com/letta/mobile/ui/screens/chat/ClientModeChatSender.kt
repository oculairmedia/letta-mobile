package com.letta.mobile.ui.screens.chat

import com.letta.mobile.bot.config.BotConfig
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
        conversationId: String?,
        forceFreshConversation: Boolean,
    ): Flow<BotStreamChunk> = flow {
        val remoteAgentId = if (forceFreshConversation) {
            clientModeController.restartSession()
        } else {
            clientModeController.ensureReady()
        }

        internalBotClient.streamMessage(
            BotChatRequest(
                message = text,
                channelId = "letta-mobile",
                chatId = "agent:$screenAgentId",
                senderId = "letta-mobile-user",
                agentId = remoteAgentId,
                conversationId = conversationId,
            )
        ).collect { emit(it) }
    }
}
