package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Conversation
import kotlinx.coroutines.flow.Flow

interface IConversationRepository {
    fun getConversations(agentId: String): Flow<List<Conversation>>
    fun getCachedConversations(agentId: String): List<Conversation>
    fun hasFreshConversations(agentId: String, maxAgeMs: Long): Boolean
    suspend fun refreshConversations(agentId: String)
    suspend fun refreshConversationsIfStale(agentId: String, maxAgeMs: Long): Boolean
    suspend fun getConversation(id: String): Conversation
    suspend fun createConversation(agentId: String, summary: String? = null): Conversation
    suspend fun deleteConversation(id: String, agentId: String)
    suspend fun updateConversation(id: String, agentId: String, summary: String)
    suspend fun setConversationArchived(id: String, agentId: String, archived: Boolean)
    suspend fun cancelConversation(id: String, agentId: String? = null)
    suspend fun recompileConversation(id: String, dryRun: Boolean = false, agentId: String? = null): String
    suspend fun forkConversation(id: String, agentId: String): Conversation
}
