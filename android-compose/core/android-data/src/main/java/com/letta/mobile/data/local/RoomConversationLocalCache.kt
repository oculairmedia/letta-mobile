package com.letta.mobile.data.local

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.ConversationLocalCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room-backed [ConversationLocalCache] for [com.letta.mobile.data.repository.CachedConversationRepository]. */
class RoomConversationLocalCache(
    private val dao: ConversationDao,
) : ConversationLocalCache {
    override suspend fun getAllOnce(): List<Conversation> =
        dao.getAllOnce().map { it.toConversation() }

    override suspend fun getAllRefreshStatesOnce(): Map<AgentId, Long> =
        dao.getAllRefreshStatesOnce().associate { AgentId(it.agentId) to it.lastRefreshAtMillis }

    override fun observeForAgent(agentId: AgentId): Flow<List<Conversation>> =
        dao.observeForAgent(agentId.value).map { rows -> rows.map { it.toConversation() } }

    override suspend fun getForAgentOnce(agentId: AgentId): List<Conversation> =
        dao.getForAgentOnce(agentId.value).map { it.toConversation() }

    override suspend fun getByIdOnce(conversationId: ConversationId): Conversation? =
        dao.getByIdOnce(conversationId.value)?.toConversation()

    override suspend fun upsert(conversation: Conversation) {
        dao.upsert(ConversationEntity.fromConversation(conversation))
    }

    override suspend fun replaceForAgent(
        agentId: AgentId,
        conversations: List<Conversation>,
        refreshedAtMillis: Long,
    ) {
        dao.replaceForAgent(
            agentId = agentId.value,
            conversations = conversations.map {
                ConversationEntity.fromConversation(it, cachedAtEpochMs = refreshedAtMillis)
            },
            refreshedAtMillis = refreshedAtMillis,
        )
    }

    override suspend fun upsertRefreshState(agentId: AgentId, lastRefreshAtMillis: Long) {
        dao.upsertRefreshState(
            ConversationRefreshEntity(agentId = agentId.value, lastRefreshAtMillis = lastRefreshAtMillis),
        )
    }

    override suspend fun deleteAll() {
        dao.deleteAll()
    }

    override suspend fun deleteAllRefreshStates() {
        dao.deleteAllRefreshStates()
    }
}
