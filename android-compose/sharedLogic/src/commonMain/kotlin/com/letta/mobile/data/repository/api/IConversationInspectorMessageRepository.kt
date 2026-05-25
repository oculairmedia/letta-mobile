package com.letta.mobile.data.repository.api

import com.letta.mobile.data.repository.ConversationInspectorMessage

interface IConversationInspectorMessageRepository {
    suspend fun fetchConversationInspectorMessages(conversationId: String): List<ConversationInspectorMessage>
}
