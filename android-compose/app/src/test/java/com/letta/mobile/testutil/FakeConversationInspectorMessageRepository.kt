package com.letta.mobile.testutil

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.ConversationInspectorMessage
import com.letta.mobile.data.repository.api.IConversationInspectorMessageRepository

class FakeConversationInspectorMessageRepository(
    initialMessagesByConversation: Map<String, List<ConversationInspectorMessage>> = emptyMap(),
) : IConversationInspectorMessageRepository {
    val messagesByConversation: MutableMap<String, List<ConversationInspectorMessage>> =
        initialMessagesByConversation.toMutableMap()

    /** Window sizes requested through [fetchLatestConversationInspectorMessages]. */
    val latestFetchLimits: MutableList<Int> = mutableListOf()

    override suspend fun fetchConversationInspectorMessages(
        conversationId: ConversationId,
    ): List<ConversationInspectorMessage> = messagesByConversation[conversationId.value].orEmpty()

    override suspend fun fetchLatestConversationInspectorMessages(
        conversationId: ConversationId,
        limit: Int,
    ): List<ConversationInspectorMessage> {
        latestFetchLimits += limit
        // Stored lists are oldest-first; mirror the server's `order=desc`
        // paging by returning the newest [limit] messages, newest first.
        return messagesByConversation[conversationId.value].orEmpty().takeLast(limit).reversed()
    }
}
