package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Conversation
import kotlinx.coroutines.flow.Flow

interface IConversationRepository {
    fun getConversations(agentId: String): Flow<List<Conversation>>
    suspend fun refreshConversations(agentId: String)
    suspend fun createConversation(agentId: String, summary: String? = null): Conversation
    suspend fun deleteConversation(id: String, agentId: String)
    suspend fun updateConversation(id: String, agentId: String, summary: String)
    suspend fun forkConversation(id: String, agentId: String): Conversation
}
