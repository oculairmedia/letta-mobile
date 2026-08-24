package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation

/**
 * Remote HTTP conversation list surface for [com.letta.mobile.data.repository.CachedAllConversationsRepository].
 */
interface ConversationRemoteSource {
    suspend fun listConversations(
        agentId: AgentId?,
        limit: Int?,
        after: String?,
        archiveStatus: String?,
        summarySearch: String?,
        order: String?,
        orderBy: String?,
    ): List<Conversation>
}

/**
 * Optional durable all-conversations cache (Room on Android). Domain [Conversation] values —
 * platforms own entity mapping.
 */
interface AllConversationsLocalCache {
    suspend fun getAllOnce(): List<Conversation>

    suspend fun upsert(conversation: Conversation)

    suspend fun upsertAll(conversations: List<Conversation>)

    suspend fun delete(conversationId: String)

    suspend fun deleteAll()

    suspend fun deleteAllRefreshStates()
}
