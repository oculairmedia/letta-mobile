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
     * In Client Mode, `conversationId == null` means the user is on an
     * explicit fresh chat route. Do not rely on null alone: live gateway/SDK
     * sessions can interpret null as "resume the active conversation". Send
     * `force_new` as the fresh-chat transport contract while still letting the
     * gateway allocate the real conversation id.
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
                forceNew = conversationId == null,
            )
        ).collect { emit(it) }
    }
}
