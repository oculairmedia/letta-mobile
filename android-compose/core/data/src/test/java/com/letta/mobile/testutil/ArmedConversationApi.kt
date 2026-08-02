package com.letta.mobile.testutil

import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.api.IrohAdminApiUnavailableException
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import io.mockk.mockk

fun armedConversationApi(): ConversationApi = object : ConversationApi(mockk(relaxed = true)) {
    override suspend fun listConversations(
        agentId: AgentId?,
        limit: Int?,
        after: String?,
        archiveStatus: String?,
        summarySearch: String?,
        order: String?,
        orderBy: String?,
    ): List<Conversation> = throw IrohAdminApiUnavailableException("iroh://armed-http")
}
