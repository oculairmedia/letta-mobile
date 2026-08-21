package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationCreateParams
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ConversationUpdateParams
import kotlinx.coroutines.flow.Flow

/**
 * Remote HTTP (or equivalent) conversation admin surface used by
 * [com.letta.mobile.data.repository.CachedConversationRepository].
 * Platform modules supply Ktor/[ConversationApi] bindings; Iroh traffic goes
 * through [ConversationIrohSource].
 */
interface ConversationRemoteSource {
    suspend fun listConversations(
        agentId: AgentId? = null,
        limit: Int? = null,
        after: String? = null,
        archiveStatus: String? = null,
        summarySearch: String? = null,
        order: String? = null,
        orderBy: String? = null,
    ): List<Conversation>

    suspend fun getConversation(conversationId: ConversationId): Conversation
    suspend fun createConversation(params: ConversationCreateParams): Conversation
    suspend fun updateConversation(
        conversationId: ConversationId,
        params: ConversationUpdateParams,
    ): Conversation

    suspend fun deleteConversation(conversationId: ConversationId)
    suspend fun cancelConversation(conversationId: ConversationId, agentId: AgentId? = null)
    suspend fun recompileConversation(
        conversationId: ConversationId,
        dryRun: Boolean = false,
        agentId: AgentId? = null,
    ): String

    suspend fun forkConversation(conversationId: ConversationId, agentId: AgentId? = null): Conversation
}

/**
 * Optional durable conversation cache (Room on Android). Domain [Conversation]
 * values — platforms own entity mapping.
 */
interface ConversationLocalCache {
    suspend fun getAllOnce(): List<Conversation>
    suspend fun getAllRefreshStatesOnce(): Map<AgentId, Long>
    fun observeForAgent(agentId: AgentId): Flow<List<Conversation>>
    suspend fun getForAgentOnce(agentId: AgentId): List<Conversation>
    suspend fun getByIdOnce(conversationId: ConversationId): Conversation?
    suspend fun upsert(conversation: Conversation)
    suspend fun replaceForAgent(
        agentId: AgentId,
        conversations: List<Conversation>,
        refreshedAtMillis: Long,
    )

    suspend fun upsertRefreshState(agentId: AgentId, lastRefreshAtMillis: Long)
    suspend fun deleteAll()
    suspend fun deleteAllRefreshStates()
}

/**
 * Iroh admin_rpc conversation surface. Implemented by
 * [com.letta.mobile.data.repository.IrohAdminRpcConversationListSource].
 */
interface ConversationIrohSource {
    fun shouldUseIroh(): Boolean
    suspend fun listConversations(
        agentId: AgentId?,
        limit: Int? = null,
        after: String? = null,
        archiveStatus: String? = null,
        summarySearch: String? = null,
        order: String? = null,
        orderBy: String? = null,
    ): List<Conversation>

    suspend fun listConversationsForAgent(agentId: AgentId, limit: Int? = null): List<Conversation>
    suspend fun getConversation(id: ConversationId): Conversation
    suspend fun createConversation(agentId: AgentId, summary: String?): Conversation
    suspend fun updateConversation(id: ConversationId, summary: String): Conversation
    suspend fun deleteConversation(id: ConversationId)
    suspend fun setConversationArchived(id: ConversationId, archived: Boolean): Conversation
}
