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
        // letta-mobile-w2hx.4: the controller no longer hands us a bound
        // agent ID — it just guarantees the transport is up. The agent
        // identity comes straight from the active chat (`screenAgentId`)
        // and rides along on `BotChatRequest.agentId`.
        if (forceFreshConversation) {
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
                agentId = screenAgentId,
                conversationId = conversationId,
                // letta-mobile-flk.6: forward the fresh-route signal to the
                // gateway so it clears its persisted conversation mapping
                // and starts a new Letta conversation. Without this flag,
                // a "New chat" tap on the same agent silently resumes the
                // previous conversation server-side because the gateway
                // falls back to its conversationStore.get(agentId).
                forceNew = forceFreshConversation,
            )
        ).collect { emit(it) }
    }
}
