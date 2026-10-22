package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationCountEstimate
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.flow.StateFlow

interface IAllConversationsRepository {
    val conversations: StateFlow<List<Conversation>>
    val hasMore: StateFlow<Boolean>

    suspend fun loadNextPage()
    suspend fun refresh()
    fun hasFreshConversations(maxAgeMs: Long): Boolean
    suspend fun refreshIfStale(maxAgeMs: Long): Boolean
    fun handleOptimisticUpdate(conversation: Conversation)
    fun handleOptimisticDelete(conversationId: ConversationId)
    fun loadedCountEstimate(): ConversationCountEstimate?
}
