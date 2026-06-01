package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.ConversationInspectorMessage

interface IConversationInspectorMessageRepository {
    suspend fun fetchConversationInspectorMessages(conversationId: ConversationId): List<ConversationInspectorMessage>
}
