package com.letta.mobile.data.repository.api

import androidx.paging.PagingData
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationCountEstimate
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface IAllConversationsRepository {
    val conversations: StateFlow<List<Conversation>>
    val hasMore: StateFlow<Boolean>

    fun getConversationsPaged(
        agentId: AgentId? = null,
        archiveStatus: String? = null,
        summarySearch: String? = null,
    ): Flow<PagingData<Conversation>>

    suspend fun loadNextPage()
    suspend fun refresh()
    fun hasFreshConversations(maxAgeMs: Long): Boolean
    suspend fun refreshIfStale(maxAgeMs: Long): Boolean
    fun handleOptimisticUpdate(conversation: Conversation)
    fun handleOptimisticDelete(conversationId: ConversationId)
    fun loadedCountEstimate(): ConversationCountEstimate?

    @Deprecated("Use loadedCountEstimate() and render approximate/unknown states explicitly.")
    suspend fun countConversations(): Int
}
