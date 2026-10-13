package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage

open class FakeMessageApi : TestMessageSource() {
    var messages = mutableListOf<LettaMessage>()
    var shouldFail = false
    val calls = mutableListOf<String>()

    override suspend fun listMessages(
        agentId: AgentId,
        limit: Int?,
        before: String?,
        after: String?,
        order: String?,
        conversationId: ConversationId?,
    ): List<LettaMessage> {
        calls.add("listMessages:$agentId")
        if (shouldFail) throw ApiException(500, "Server error")
        return messages
    }

    override suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> {
        calls.add("listConversationMessages:$conversationId")
        if (shouldFail) throw ApiException(500, "Server error")
        return messages
    }

}
