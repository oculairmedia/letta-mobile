package com.letta.mobile.bot.chat

import com.letta.mobile.bot.protocol.BotStreamChunk
import com.letta.mobile.data.model.MessageContentPart
import kotlinx.coroutines.flow.Flow

interface IClientModeChatSender {
    fun streamMessage(
        screenAgentId: String,
        text: String,
        conversationId: String?,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ): Flow<BotStreamChunk>
}
