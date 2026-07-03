package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.ConversationInspectorMessage

interface IConversationInspectorMessageRepository {
    suspend fun fetchConversationInspectorMessages(conversationId: ConversationId): List<ConversationInspectorMessage>

    /**
     * Newest-first window of the latest [limit] inspector messages.
     *
     * The heartbeat notifier only needs the most recent notifiable message;
     * scanning the default oldest-first page both pins detection to a
     * conversation's first 200 messages and pulls up to 200 records per
     * conversation per sync (letta-mobile-e9vca). Remote implementations
     * override this with an `order=desc` query so only [limit] records cross
     * the wire; the default derives the window from the full fetch so
     * existing implementations and test doubles stay correct.
     */
    suspend fun fetchLatestConversationInspectorMessages(
        conversationId: ConversationId,
        limit: Int,
    ): List<ConversationInspectorMessage> =
        fetchConversationInspectorMessages(conversationId).takeLast(limit).reversed()
}
