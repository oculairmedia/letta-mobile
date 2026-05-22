package com.letta.mobile.data.repository

import android.util.Log
import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.ConversationEntity
import com.letta.mobile.data.local.ConversationRefreshEntity
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationCreateParams
import com.letta.mobile.data.model.ConversationUpdateParams
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IConversationRepository
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationApi: ConversationApi,
    private val agentRepository: IAgentRepository,
    private val conversationDao: ConversationDao,
) : IConversationRepository {
    private val _conversationsByAgent = MutableStateFlow<Map<String, List<Conversation>>>(emptyMap())
    private val refreshMutex = Mutex()
    private val lastRefreshAtMillisByAgent = mutableMapOf<String, Long>()
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        repositoryScope.launch {
            try {
                val cached = conversationDao.getAllOnce().map { it.toConversation() }
                if (cached.isNotEmpty()) {
                    _conversationsByAgent.value = cached.groupBy { it.agentId }
                }
                conversationDao.getAllRefreshStatesOnce().forEach { state ->
                    lastRefreshAtMillisByAgent[state.agentId] = state.lastRefreshAtMillis
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load cached conversations", e)
            }
        }
    }

    override fun getConversations(agentId: String): Flow<List<Conversation>> {
        return conversationDao.observeForAgent(agentId).map { rows ->
            rows.map { it.toConversation() }.also { conversations ->
                updateMemoryCache(agentId, conversations)
            }
        }
    }

    override suspend fun refreshConversations(agentId: String) = refreshMutex.withLock {
        refreshConversationsLocked(agentId)
    }

    private suspend fun refreshConversationsLocked(agentId: String) {
        val conversations = conversationApi.listConversations(agentId = agentId)
        val refreshedAt = System.currentTimeMillis()
        writeAgentConversations(agentId, conversations, refreshedAt)
    }

    override fun getCachedConversations(agentId: String): List<Conversation> = _conversationsByAgent.value[agentId] ?: emptyList()

    override fun hasFreshConversations(agentId: String, maxAgeMs: Long): Boolean {
        val lastRefreshAt = lastRefreshAtMillisByAgent[agentId] ?: return false
        return System.currentTimeMillis() - lastRefreshAt <= maxAgeMs
    }

    override suspend fun refreshConversationsIfStale(agentId: String, maxAgeMs: Long): Boolean = refreshMutex.withLock {
        if (hasFreshConversations(agentId, maxAgeMs)) return@withLock false
        refreshConversationsLocked(agentId)
        true
    }

    override suspend fun getConversation(id: String): Conversation {
        return try {
            conversationApi.getConversation(id).also { conversation ->
                upsertCachedConversation(conversation)
            }
        } catch (e: Exception) {
            conversationDao.getByIdOnce(id)?.toConversation() ?: throw e
        }
    }

    override suspend fun createConversation(agentId: String, summary: String?): Conversation {
        val params = ConversationCreateParams(agentId = agentId, summary = summary)
        val conversation = conversationApi.createConversation(params)
        upsertCachedConversation(conversation, markAgentFresh = true)
        return conversation
    }

    override suspend fun deleteConversation(id: String, agentId: String) {
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

    override suspend fun updateConversation(id: String, agentId: String, summary: String) {
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

    override suspend fun setConversationArchived(id: String, agentId: String, archived: Boolean) {
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

    override suspend fun cancelConversation(id: String, agentId: String?) {
        conversationApi.cancelConversation(id, agentId)
    }

    override suspend fun recompileConversation(id: String, dryRun: Boolean, agentId: String?): String {
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

    override suspend fun forkConversation(id: String, agentId: String): Conversation {
        val conversation = conversationApi.forkConversation(id, agentId)
        upsertCachedConversation(conversation, markAgentFresh = true)
        return conversation
    }

    private suspend fun snapshotForAgent(agentId: String): List<Conversation> {
        return getCachedConversations(agentId).ifEmpty {
            conversationDao.getForAgentOnce(agentId).map { it.toConversation() }
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
                ConversationRefreshEntity(agentId = conversation.agentId, lastRefreshAtMillis = refreshedAt),
            )
        }
    }

    private suspend fun writeAgentConversations(
        agentId: String,
        conversations: List<Conversation>,
        refreshedAtMillis: Long,
    ) {
        conversationDao.replaceForAgent(
            agentId = agentId,
            conversations = conversations.map { ConversationEntity.fromConversation(it, cachedAtEpochMs = refreshedAtMillis) },
            refreshedAtMillis = refreshedAtMillis,
        )
        updateMemoryCache(agentId, conversations)
        lastRefreshAtMillisByAgent[agentId] = refreshedAtMillis
    }

    private fun updateMemoryCache(agentId: String, conversations: List<Conversation>) {
        _conversationsByAgent.update { current ->
            current.toMutableMap().apply { put(agentId, conversations) }
        }
    }

    private companion object {
        const val TAG = "ConversationRepository"
    }
}
