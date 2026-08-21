package com.letta.mobile.data.repository

import com.letta.mobile.data.repository.api.MessageIrohTimelineSource
import com.letta.mobile.data.repository.api.MessageTimelinePage
import com.letta.mobile.data.timeline.IrohAdminRpcTimelineTransport

/** Adapts [IrohAdminRpcTimelineTransport] to [MessageIrohTimelineSource] for [CachedMessageRepository]. */
class IrohAdminRpcMessageTimelineSource(
    private val transport: IrohAdminRpcTimelineTransport,
) : MessageIrohTimelineSource {
    override fun shouldUseIroh(): Boolean = transport.shouldUseIroh()

    override suspend fun listOlderConversationMessages(
        conversationId: String,
        beforeMessageId: String,
        limit: Int,
    ) = transport.listOlderConversationMessages(
        conversationId = conversationId,
        beforeMessageId = beforeMessageId,
        limit = limit,
    )

    override suspend fun listOlderConversationMessagesPage(
        conversationId: String,
        beforeMessageId: String,
        limit: Int,
    ): MessageTimelinePage {
        val page = transport.listOlderConversationMessagesPage(
            conversationId = conversationId,
            beforeMessageId = beforeMessageId,
            limit = limit,
        )
        return MessageTimelinePage(messages = page.messages, hasMore = page.hasMore)
    }
}
