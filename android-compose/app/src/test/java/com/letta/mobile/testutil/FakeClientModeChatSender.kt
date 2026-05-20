package com.letta.mobile.testutil

import com.letta.mobile.bot.chat.IClientModeChatSender
import com.letta.mobile.bot.protocol.BotStreamChunk
import com.letta.mobile.data.model.MessageContentPart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class FakeClientModeChatSender : IClientModeChatSender {
    var stream: Flow<BotStreamChunk> = emptyFlow()
    val requests: MutableList<Request> = mutableListOf()

    override fun streamMessage(
        screenAgentId: String,
        text: String,
        conversationId: String?,
        attachments: List<MessageContentPart.Image>,
    ): Flow<BotStreamChunk> {
        requests += Request(screenAgentId, text, conversationId, attachments)
        return stream
    }

    data class Request(
        val screenAgentId: String,
        val text: String,
        val conversationId: String?,
        val attachments: List<MessageContentPart.Image>,
    )
}
