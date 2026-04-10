package com.letta.mobile.testutil

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LettaResponse
import com.letta.mobile.data.model.MessageCreateRequest
import io.mockk.mockk

class FakeMessageApi : MessageApi(mockk(relaxed = true)) {
    var messages = mutableListOf<LettaMessage>()

    override suspend fun listMessages(
        agentId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = messages

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = messages
}
