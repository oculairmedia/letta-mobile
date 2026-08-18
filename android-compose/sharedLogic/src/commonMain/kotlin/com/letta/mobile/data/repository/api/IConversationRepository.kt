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
    fun hasFreshConversations(agentId: AgentId, maxAgeMs: Long): Boolean = false
    fun hasFreshConversations(agentId: String, maxAgeMs: Long): Boolean = hasFreshConversations(AgentId(agentId), maxAgeMs)
    suspend fun refreshConversations(agentId: AgentId) = Unit
    suspend fun refreshConversations(agentId: String) = refreshConversations(AgentId(agentId))
    suspend fun refreshConversationsIfStale(agentId: AgentId, maxAgeMs: Long): Boolean = false
    suspend fun refreshConversationsIfStale(agentId: String, maxAgeMs: Long): Boolean = refreshConversationsIfStale(AgentId(agentId), maxAgeMs)
    suspend fun getConversation(id: ConversationId): Conversation = error("getConversation unsupported")
    suspend fun getConversation(id: String): Conversation = getConversation(ConversationId(id))
    suspend fun createConversation(agentId: AgentId, summary: String? = null): Conversation = error("createConversation unsupported")
    suspend fun createConversation(agentId: String, summary: String? = null): Conversation = createConversation(AgentId(agentId), summary)
    suspend fun deleteConversation(id: ConversationId, agentId: AgentId): Unit = error("deleteConversation unsupported")
    suspend fun deleteConversation(id: String, agentId: String): Unit = deleteConversation(ConversationId(id), AgentId(agentId))
    suspend fun updateConversation(id: ConversationId, agentId: AgentId, summary: String): Unit = error("updateConversation unsupported")
    suspend fun updateConversation(id: String, agentId: String, summary: String): Unit = updateConversation(ConversationId(id), AgentId(agentId), summary)
    suspend fun setConversationArchived(id: ConversationId, agentId: AgentId, archived: Boolean): Unit = error("setConversationArchived unsupported")
    suspend fun setConversationArchived(id: String, agentId: String, archived: Boolean): Unit = setConversationArchived(ConversationId(id), AgentId(agentId), archived)
    suspend fun cancelConversation(id: ConversationId, agentId: AgentId? = null): Unit = error("cancelConversation unsupported")
    suspend fun cancelConversation(id: String, agentId: String? = null): Unit = cancelConversation(ConversationId(id), agentId?.let(::AgentId))
    suspend fun recompileConversation(id: ConversationId, dryRun: Boolean = false, agentId: AgentId? = null): String = error("recompileConversation unsupported")
    suspend fun recompileConversation(id: String, dryRun: Boolean = false, agentId: String? = null): String =
        recompileConversation(ConversationId(id), dryRun, agentId?.let(::AgentId))
    suspend fun forkConversation(id: ConversationId, agentId: AgentId): Conversation = error("forkConversation unsupported")
    suspend fun forkConversation(id: String, agentId: String): Conversation = forkConversation(ConversationId(id), AgentId(agentId))

    // letta-mobile-i9h61.3.2: agent-scoped conversation list (the
    // OTHER agent's conversations, for tap-to-navigate on inter-agent
    // messages). Default implementation returns empty so fakes and
    // stale gateways don't break; real impls override to call the
    // conversation.list_agent admin_rpc.
    suspend fun listConversationsForAgent(
        agentId: AgentId,
        limit: Int = 40,
    ): List<Conversation> = emptyList()
    suspend fun listConversationsForAgent(
        agentId: String,
        limit: Int = 40,
    ): List<Conversation> = listConversationsForAgent(AgentId(agentId), limit)
}
