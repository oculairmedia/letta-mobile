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
    /**
     * letta-mobile-w2hx.7: the prior `forceFreshConversation` parameter
     * is gone. A "New chat" tap surfaces here as `conversationId == null`,
     * which the gateway reads as "open a fresh Letta conversation". There
     * is no longer a transport-level flag, and we no longer need to
     * restart the WS session to invalidate a per-agent server-side conv
     * map (it was deleted in w2hx.6).
     */
    fun streamMessage(
        screenAgentId: String,
        text: String,
        conversationId: String?,
    ): Flow<BotStreamChunk> = flow {
        // letta-mobile-w2hx.4: the controller no longer hands us a bound
        // agent ID — it just guarantees the transport is up. The agent
        // identity comes straight from the active chat (`screenAgentId`)
        // and rides along on `BotChatRequest.agentId`.
        clientModeController.ensureReady()

        internalBotClient.streamMessage(
            BotChatRequest(
                message = text,
                channelId = "letta-mobile",
                chatId = "agent:$screenAgentId",
                senderId = "letta-mobile-user",
                agentId = screenAgentId,
                conversationId = conversationId,
            )
        ).collect { emit(it) }
    }
}
