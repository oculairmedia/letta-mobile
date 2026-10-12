package com.letta.mobile.testutil

import com.letta.mobile.data.repository.api.ConversationCrudRemoteSource
import com.letta.mobile.data.backend.NoAppServerRouteException
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation

fun armedConversationApi(): ConversationCrudRemoteSource = object : ConversationCrudRemoteSource {
    override suspend fun listConversations(
        agentId: AgentId?,
        limit: Int?,
        after: String?,
        archiveStatus: String?,
        summarySearch: String?,
        order: String?,
        orderBy: String?,
    ): List<Conversation> = throw NoAppServerRouteException("iroh://armed-http")
}
