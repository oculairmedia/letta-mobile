package com.letta.mobile.bot.chat

import com.letta.mobile.bot.clientmode.IClientModeController
import com.letta.mobile.bot.config.BotConfig
import com.letta.mobile.bot.protocol.BotChatRequest
import com.letta.mobile.bot.protocol.BotMessageContentItem
import com.letta.mobile.bot.protocol.BotStreamChunk
import com.letta.mobile.bot.protocol.BotClient
import com.letta.mobile.data.model.MessageContentPart
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect

@Singleton
class ClientModeChatSender @Inject constructor(
    private val internalBotClient: BotClient,
    private val clientModeController: IClientModeController,
) : IClientModeChatSender {
    /**
     * In Client Mode, `conversationId == null` means the user is on an
     * explicit fresh chat route. Do not rely on null alone: live gateway/SDK
     * sessions can interpret null as "resume the active conversation". Send
     * `force_new` as the fresh-chat transport contract while still letting the
     * gateway allocate the real conversation id.
     */
    override fun streamMessage(
        screenAgentId: String,
        text: String,
        conversationId: String?,
        attachments: List<MessageContentPart.Image>,
    ): Flow<BotStreamChunk> = flow {
        // letta-mobile-w2hx.4: the controller no longer hands us a bound
        // agent ID — it just guarantees the transport is up. The agent
        // identity comes straight from the active chat (`screenAgentId`)
        // and rides along on `BotChatRequest.agentId`.
        clientModeController.ensureReady()

        internalBotClient.streamMessage(
            BotChatRequest(
                message = text,
                contentItems = attachments.toBotContentItems(text),
                channelId = "letta-mobile",
                chatId = "agent:$screenAgentId",
                senderId = "letta-mobile-user",
                agentId = screenAgentId,
                conversationId = conversationId,
                forceNew = conversationId == null,
            )
        ).collect { emit(it) }
    }

    fun streamMessage(
        screenAgentId: String,
        text: String,
        conversationId: String?,
    ): Flow<BotStreamChunk> = streamMessage(screenAgentId, text, conversationId, emptyList())
}

private fun List<MessageContentPart.Image>.toBotContentItems(text: String): List<BotMessageContentItem>? {
    if (isEmpty()) return null
    return buildList {
        if (text.isNotBlank()) add(BotMessageContentItem.text(text))
        for (image in this@toBotContentItems) {
            add(
                BotMessageContentItem.image(
                    base64 = image.base64,
                    mediaType = image.mediaType,
                )
            )
        }
    }
}
