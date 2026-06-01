package com.letta.mobile.data.repository

import android.util.Log
import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.ConversationEntity
import com.letta.mobile.data.local.ConversationRefreshEntity
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationCreateParams
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ConversationUpdateParams
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.session.BackendScopedCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun defaultConversationRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

open class ConversationRepository(
    private val conversationApi: ConversationApi,
    private val agentRepository: IAgentRepository,
    private val conversationDao: ConversationDao,
    private val repositoryScope: CoroutineScope = defaultConversationRepositoryScope(),
) : IConversationRepository, BackendScopedCache {
    private val _conversationsByAgent = MutableStateFlow<Map<AgentId, List<Conversation>>>(emptyMap())
    private val refreshMutex = Mutex()
    private val lastRefreshAtMillisByAgent = mutableMapOf<AgentId, Long>()

    init {
        repositoryScope.launch {
            try {
                val cached = conversationDao.getAllOnce().map { it.toConversation() }
                if (cached.isNotEmpty()) {
                    _conversationsByAgent.value = cached.groupBy { it.agentId }
                }
                conversationDao.getAllRefreshStatesOnce().forEach { state ->
                    lastRefreshAtMillisByAgent[AgentId(state.agentId)] = state.lastRefreshAtMillis
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load cached conversations", e)
            }
        }
    }

    override fun getConversations(agentId: AgentId): Flow<List<Conversation>> {
        return conversationDao.observeForAgent(agentId.value).map { rows ->
            rows.map { it.toConversation() }.also { conversations ->
                updateMemoryCache(agentId, conversations)
            }
        }
    }

    override suspend fun refreshConversations(agentId: AgentId) = refreshMutex.withLock {
        refreshConversationsLocked(agentId)
    }

    override suspend fun clearForBackendSwitch() {
        refreshMutex.withLock {
            _conversationsByAgent.value = emptyMap()
            lastRefreshAtMillisByAgent.clear()
            // Propagate DAO failure. See AgentRepository.clearForBackendSwitch
            // for the rationale.
            conversationDao.deleteAll()
            conversationDao.deleteAllRefreshStates()
        }
    }

    private suspend fun refreshConversationsLocked(agentId: AgentId) {
        val conversations = conversationApi.listConversations(agentId = agentId)
        val refreshedAt = System.currentTimeMillis()
        writeAgentConversations(agentId, conversations, refreshedAt)
    }

    override fun getCachedConversations(agentId: AgentId): List<Conversation> = _conversationsByAgent.value[agentId] ?: emptyList()

    override fun hasFreshConversations(agentId: AgentId, maxAgeMs: Long): Boolean {
        val lastRefreshAt = lastRefreshAtMillisByAgent[agentId] ?: return false
        return System.currentTimeMillis() - lastRefreshAt <= maxAgeMs
    }

    override suspend fun refreshConversationsIfStale(agentId: AgentId, maxAgeMs: Long): Boolean = refreshMutex.withLock {
        if (hasFreshConversations(agentId, maxAgeMs)) return@withLock false
        refreshConversationsLocked(agentId)
        true
    }

    override suspend fun getConversation(id: ConversationId): Conversation {
        return try {
            conversationApi.getConversation(id).also { conversation ->
                upsertCachedConversation(conversation)
            }
        } catch (e: Exception) {
            conversationDao.getByIdOnce(id.value)?.toConversation() ?: throw e
        }
    }

    override suspend fun createConversation(agentId: AgentId, summary: String?): Conversation {
        val params = ConversationCreateParams(agentId = agentId, summary = summary)
        val conversation = conversationApi.createConversation(params)
        upsertCachedConversation(conversation, markAgentFresh = true)
        return conversation
    }

    override suspend fun deleteConversation(id: ConversationId, agentId: AgentId) {
        val snapshot = snapshotForAgent(agentId)
        val optimistic = snapshot.filter { it.id != id }
        writeAgentConversations(agentId, optimistic, System.currentTimeMillis())

        try {
            conversationApi.deleteConversation(id)
        } catch (e: Exception) {
            writeAgentConversations(agentId, snapshot, System.currentTimeMillis())
            throw e
        }
    }

    override suspend fun updateConversation(id: ConversationId, agentId: AgentId, summary: String) {
        val snapshot = snapshotForAgent(agentId)
        val conversationIndex = snapshot.indexOfFirst { it.id == id }
        if (conversationIndex < 0) return

        val optimisticList = snapshot.toMutableList()
        optimisticList[conversationIndex] = snapshot[conversationIndex].copy(summary = summary)
        writeAgentConversations(agentId, optimisticList, System.currentTimeMillis())

        try {
            val params = ConversationUpdateParams(summary = summary)
            val updated = conversationApi.updateConversation(id, params)
            writeAgentConversations(
                agentId = agentId,
                conversations = optimisticList.map { if (it.id == updated.id) updated else it },
                refreshedAtMillis = System.currentTimeMillis(),
            )
        } catch (e: Exception) {
            writeAgentConversations(agentId, snapshot, System.currentTimeMillis())
            throw e
        }
    }

    override suspend fun setConversationArchived(id: ConversationId, agentId: AgentId, archived: Boolean) {
        val snapshot = snapshotForAgent(agentId)
        val conversationIndex = snapshot.indexOfFirst { it.id == id }
        if (conversationIndex < 0) return

        val optimisticList = snapshot.toMutableList()
        optimisticList[conversationIndex] = snapshot[conversationIndex].copy(archived = archived)
        writeAgentConversations(agentId, optimisticList, System.currentTimeMillis())

        try {
            val params = ConversationUpdateParams(archived = archived)
            val updated = conversationApi.updateConversation(id, params)
            writeAgentConversations(
                agentId = agentId,
                conversations = optimisticList.map { if (it.id == updated.id) updated else it },
                refreshedAtMillis = System.currentTimeMillis(),
            )
        } catch (e: Exception) {
            writeAgentConversations(agentId, snapshot, System.currentTimeMillis())
            throw e
        }
    }

    override suspend fun cancelConversation(id: ConversationId, agentId: AgentId?) {
        conversationApi.cancelConversation(id, agentId)
    }

    override suspend fun recompileConversation(id: ConversationId, dryRun: Boolean, agentId: AgentId?): String {
        return if (agentId != null && !dryRun) {
            var result = ""
            agentRepository.checkpointAndRestoreConfig(agentId) {
                result = conversationApi.recompileConversation(id, dryRun, agentId)
            }
            result
        } else {
            conversationApi.recompileConversation(id, dryRun, agentId)
        }
    }

    override suspend fun forkConversation(id: ConversationId, agentId: AgentId): Conversation {
        val conversation = conversationApi.forkConversation(id, agentId)
        upsertCachedConversation(conversation, markAgentFresh = true)
        return conversation
    }

    private suspend fun snapshotForAgent(agentId: AgentId): List<Conversation> {
        return getCachedConversations(agentId).ifEmpty {
            conversationDao.getForAgentOnce(agentId.value).map { it.toConversation() }
        }
    }

    private suspend fun upsertCachedConversation(conversation: Conversation, markAgentFresh: Boolean = false) {
        conversationDao.upsert(ConversationEntity.fromConversation(conversation))
        val current = snapshotForAgent(conversation.agentId)
        val updated = listOf(conversation) + current.filterNot { it.id == conversation.id }
        updateMemoryCache(conversation.agentId, updated)
        if (markAgentFresh) {
            val refreshedAt = System.currentTimeMillis()
            lastRefreshAtMillisByAgent[conversation.agentId] = refreshedAt
            conversationDao.upsertRefreshState(
                ConversationRefreshEntity(agentId = conversation.agentId.value, lastRefreshAtMillis = refreshedAt),
            )
        }
    }

    private suspend fun writeAgentConversations(
        agentId: AgentId,
        conversations: List<Conversation>,
        refreshedAtMillis: Long,
    ) {
        conversationDao.replaceForAgent(
            agentId = agentId.value,
            conversations = conversations.map { ConversationEntity.fromConversation(it, cachedAtEpochMs = refreshedAtMillis) },
            refreshedAtMillis = refreshedAtMillis,
        )
        updateMemoryCache(agentId, conversations)
        lastRefreshAtMillisByAgent[agentId] = refreshedAtMillis
    }

    private fun updateMemoryCache(agentId: AgentId, conversations: List<Conversation>) {
        _conversationsByAgent.update { current ->
            current.toMutableMap().apply { put(agentId, conversations) }
        }
    }

    private companion object {
        const val TAG = "ConversationRepository"
    }
}
