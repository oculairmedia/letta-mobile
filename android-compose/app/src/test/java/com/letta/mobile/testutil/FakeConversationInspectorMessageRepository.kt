package com.letta.mobile.testutil

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.ConversationInspectorMessage
import com.letta.mobile.data.repository.api.IConversationInspectorMessageRepository

class FakeConversationInspectorMessageRepository(
    initialMessagesByConversation: Map<String, List<ConversationInspectorMessage>> = emptyMap(),
) : IConversationInspectorMessageRepository {
    val messagesByConversation: MutableMap<String, List<ConversationInspectorMessage>> =
        initialMessagesByConversation.toMutableMap()

    override suspend fun fetchConversationInspectorMessages(
        conversationId: ConversationId,
    ): List<ConversationInspectorMessage> = messagesByConversation[conversationId.value].orEmpty()
}
