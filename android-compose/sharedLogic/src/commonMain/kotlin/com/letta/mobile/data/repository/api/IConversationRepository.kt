package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.flow.Flow

interface IConversationRepository {
    fun getConversations(agentId: AgentId): Flow<List<Conversation>>
    fun getConversations(agentId: String): Flow<List<Conversation>> = getConversations(AgentId(agentId))
    fun getCachedConversations(agentId: AgentId): List<Conversation>
    fun getCachedConversations(agentId: String): List<Conversation> = getCachedConversations(AgentId(agentId))
    fun hasFreshConversations(agentId: AgentId, maxAgeMs: Long): Boolean
    fun hasFreshConversations(agentId: String, maxAgeMs: Long): Boolean = hasFreshConversations(AgentId(agentId), maxAgeMs)
    suspend fun refreshConversations(agentId: AgentId)
    suspend fun refreshConversations(agentId: String) = refreshConversations(AgentId(agentId))
    suspend fun refreshConversationsIfStale(agentId: AgentId, maxAgeMs: Long): Boolean
    suspend fun refreshConversationsIfStale(agentId: String, maxAgeMs: Long): Boolean = refreshConversationsIfStale(AgentId(agentId), maxAgeMs)
    suspend fun getConversation(id: ConversationId): Conversation
    suspend fun getConversation(id: String): Conversation = getConversation(ConversationId(id))
    suspend fun createConversation(agentId: AgentId, summary: String? = null): Conversation
    suspend fun createConversation(agentId: String, summary: String? = null): Conversation = createConversation(AgentId(agentId), summary)
    suspend fun deleteConversation(id: ConversationId, agentId: AgentId)
    suspend fun deleteConversation(id: String, agentId: String) = deleteConversation(ConversationId(id), AgentId(agentId))
    suspend fun updateConversation(id: ConversationId, agentId: AgentId, summary: String)
    suspend fun updateConversation(id: String, agentId: String, summary: String) = updateConversation(ConversationId(id), AgentId(agentId), summary)
    suspend fun setConversationArchived(id: ConversationId, agentId: AgentId, archived: Boolean)
    suspend fun setConversationArchived(id: String, agentId: String, archived: Boolean) = setConversationArchived(ConversationId(id), AgentId(agentId), archived)
    suspend fun cancelConversation(id: ConversationId, agentId: AgentId? = null)
    suspend fun cancelConversation(id: String, agentId: String? = null) = cancelConversation(ConversationId(id), agentId?.let(::AgentId))
    suspend fun recompileConversation(id: ConversationId, dryRun: Boolean = false, agentId: AgentId? = null): String
    suspend fun recompileConversation(id: String, dryRun: Boolean = false, agentId: String? = null): String =
        recompileConversation(ConversationId(id), dryRun, agentId?.let(::AgentId))
    suspend fun forkConversation(id: ConversationId, agentId: AgentId): Conversation
    suspend fun forkConversation(id: String, agentId: String): Conversation = forkConversation(ConversationId(id), AgentId(agentId))
}
