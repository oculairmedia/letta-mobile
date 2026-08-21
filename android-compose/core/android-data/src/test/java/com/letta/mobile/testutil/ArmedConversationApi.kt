package com.letta.mobile.testutil

import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.api.IrohAdminApiUnavailableException
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationListParams
import io.mockk.mockk

fun armedConversationApi(): ConversationApi = object : ConversationApi(mockk(relaxed = true)) {
    override suspend fun listConversations(params: ConversationListParams): List<Conversation> =
        throw IrohAdminApiUnavailableException("iroh://armed-http")
}
