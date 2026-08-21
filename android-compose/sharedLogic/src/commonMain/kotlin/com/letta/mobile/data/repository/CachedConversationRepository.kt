package com.letta.mobile.data.repository

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentRuntimeBinding
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationCreateParams
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ConversationUpdateParams
import com.letta.mobile.data.repository.api.ConversationIrohSource
import com.letta.mobile.data.repository.api.ConversationLocalCache
import com.letta.mobile.data.repository.api.ConversationRemoteSource
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.LocalRuntimeConversationSource
import com.letta.mobile.data.session.BackendScopedCache
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
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
import kotlin.time.Clock

fun defaultCachedConversationRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Phase 5b: platform-neutral cached conversation repository. Android supplies
 * Room via [localCache]; Iroh via [irohConversationSource].
 */
open class CachedConversationRepository(
    private val remote: ConversationRemoteSource,
    private val agentRepository: IAgentRepository,
    private val localCache: (() -> ConversationLocalCache)? = null,
    repositoryScope: CoroutineScope = defaultCachedConversationRepositoryScope(),
    private val localConversationSource: LocalRuntimeConversationSource? = null,
    private val settingsRepository: ISettingsRepository? = null,
    private val irohConversationSource: ConversationIrohSource? = null,
) : IConversationRepository, BackendScopedCache {
    private val _conversationsByAgent = MutableStateFlow<Map<AgentId, List<Conversation>>>(emptyMap())
    private val refreshMutex = Mutex()
    private val lastRefreshAtMillisByAgent = mutableMapOf<AgentId, Long>()

    init {
        repositoryScope.launch {
            try {
                val cache = localCache?.invoke() ?: return@launch
                val cached = cache.getAllOnce()
                val refreshStates = cache.getAllRefreshStatesOnce()
                refreshMutex.withLock {
                    // Do not overwrite a refresh that completed while getAllOnce
                    // was suspended — that would publish a stale roster while
                    // lastRefreshAtMillis still looks fresh.
                    if (cached.isNotEmpty() && _conversationsByAgent.value.isEmpty()) {
                        _conversationsByAgent.value = cached.groupBy { it.agentId }
                    }
                    if (lastRefreshAtMillisByAgent.isEmpty() && refreshStates.isNotEmpty()) {
                        lastRefreshAtMillisByAgent.putAll(refreshStates)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Telemetry.event(
                    "CachedConversationRepository",
                    "Failed to load cached conversations",
                    "error" to (e.message ?: e.toString()),
                    level = Telemetry.Level.WARN,
                )
            }
        }
    }

    override fun getConversations(agentId: AgentId): Flow<List<Conversation>> {
        val cache = localCache
        if (cache == null) {
            return _conversationsByAgent.map { it[agentId] ?: emptyList() }
        }
        return cache().observeForAgent(agentId).map { conversations ->
            updateMemoryCache(agentId, conversations)
            conversations
        }
    }

    override suspend fun refreshConversations(agentId: AgentId) = refreshMutex.withLock {
        refreshConversationsLocked(agentId)
    }

    override suspend fun clearForBackendSwitch() {
        refreshMutex.withLock {
            _conversationsByAgent.value = emptyMap()
            lastRefreshAtMillisByAgent.clear()
            // Propagate DAO failure. See CachedAgentRepository.clearForBackendSwitch
            // for the rationale.
            val cache = localCache?.invoke() ?: return@withLock
            cache.deleteAll()
            cache.deleteAllRefreshStates()
        }
    }

    private suspend fun refreshConversationsLocked(agentId: AgentId) {
        val irohSource = irohConversationSource
        val conversations = if (irohSource?.shouldUseIroh() == true) {
            irohSource.listConversations(agentId = agentId)
        } else {
            remote.listConversations(agentId = agentId)
        }
        writeAgentConversations(agentId, conversations, nowMillis())
    }

    override fun getCachedConversations(agentId: AgentId): List<Conversation> =
        _conversationsByAgent.value[agentId] ?: emptyList()

    // letta-mobile-i9h61.3.2: agent-scoped list for tap-to-navigate.
    // The picker calls this on the OTHER agent (not the current one),
    // so caching is not meaningful — each tap fetches fresh. Default
    // IConversationRepository impl returns empty, so any gateway
    // without the agent-scoped surface degrades cleanly.
    override suspend fun listConversationsForAgent(
        agentId: AgentId,
        limit: Int,
    ): List<Conversation> = refreshMutex.withLock {
        val irohSource = irohConversationSource
        if (irohSource?.shouldUseIroh() != true) {
            return@withLock emptyList()
        }
        irohSource.listConversationsForAgent(agentId, limit)
    }

    override fun hasFreshConversations(agentId: AgentId, maxAgeMs: Long): Boolean {
        val lastRefreshAt = lastRefreshAtMillisByAgent[agentId] ?: return false
        return nowMillis() - lastRefreshAt <= maxAgeMs
    }

    override suspend fun refreshConversationsIfStale(agentId: AgentId, maxAgeMs: Long): Boolean =
        refreshMutex.withLock {
            if (hasFreshConversations(agentId, maxAgeMs)) return@withLock false
            refreshConversationsLocked(agentId)
            true
        }

    override suspend fun getConversation(id: ConversationId): Conversation {
        return try {
            val irohSource = irohConversationSource
            val fetched = if (irohSource?.shouldUseIroh() == true) {
                irohSource.getConversation(id)
            } else {
                remote.getConversation(id)
            }
            fetched.also { conversation -> upsertCachedConversation(conversation) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            localCache?.invoke()?.getByIdOnce(id) ?: throw e
        }
    }

    override suspend fun createConversation(agentId: AgentId, summary: String?): Conversation {
        val irohSource = irohConversationSource
        val conversation = when {
            localConversationSource != null &&
                AgentRuntimeBinding.isLocalRuntime(settingsRepository?.activeConfig?.value) ->
                localConversationSource.createConversation(agentId, summary)
            irohSource?.shouldUseIroh() == true ->
                irohSource.createConversation(agentId, summary)
            else -> {
                val params = ConversationCreateParams(agentId = agentId, summary = summary)
                remote.createConversation(params)
            }
        }
        upsertCachedConversation(conversation, markAgentFresh = true)
        return conversation
    }

    override suspend fun deleteConversation(id: ConversationId, agentId: AgentId) {
        val snapshot = snapshotForAgent(agentId)
        val optimistic = snapshot.filter { it.id != id }
        writeAgentConversations(agentId, optimistic, nowMillis())

        try {
            val irohSource = irohConversationSource
            if (irohSource?.shouldUseIroh() == true) {
                irohSource.deleteConversation(id)
            } else {
                remote.deleteConversation(id)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            writeAgentConversations(agentId, snapshot, nowMillis())
            throw e
        }
    }

    override suspend fun updateConversation(id: ConversationId, agentId: AgentId, summary: String) {
        val snapshot = snapshotForAgent(agentId)
        val conversationIndex = snapshot.indexOfFirst { it.id == id }
        if (conversationIndex < 0) return

        val optimisticList = snapshot.toMutableList()
        optimisticList[conversationIndex] = snapshot[conversationIndex].copy(summary = summary)
        writeAgentConversations(agentId, optimisticList, nowMillis())

        try {
            val irohSource = irohConversationSource
            val updated = if (irohSource?.shouldUseIroh() == true) {
                irohSource.updateConversation(id, summary)
            } else {
                remote.updateConversation(id, ConversationUpdateParams(summary = summary))
            }
            writeAgentConversations(
                agentId = agentId,
                conversations = optimisticList.map { if (it.id == updated.id) updated else it },
                refreshedAtMillis = nowMillis(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            writeAgentConversations(agentId, snapshot, nowMillis())
            throw e
        }
    }

    override suspend fun setConversationArchived(id: ConversationId, agentId: AgentId, archived: Boolean) {
        val snapshot = snapshotForAgent(agentId)
        val conversationIndex = snapshot.indexOfFirst { it.id == id }
        if (conversationIndex < 0) return

        val optimisticList = snapshot.toMutableList()
        optimisticList[conversationIndex] = snapshot[conversationIndex].copy(archived = archived)
        writeAgentConversations(agentId, optimisticList, nowMillis())

        try {
            val irohSource = irohConversationSource
            val updated = if (irohSource?.shouldUseIroh() == true) {
                irohSource.setConversationArchived(id, archived)
            } else {
                remote.updateConversation(id, ConversationUpdateParams(archived = archived))
            }
            writeAgentConversations(
                agentId = agentId,
                conversations = optimisticList.map { if (it.id == updated.id) updated else it },
                refreshedAtMillis = nowMillis(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            writeAgentConversations(agentId, snapshot, nowMillis())
            throw e
        }
    }

    override suspend fun cancelConversation(id: ConversationId, agentId: AgentId?) {
        remote.cancelConversation(id, agentId)
    }

    override suspend fun recompileConversation(id: ConversationId, dryRun: Boolean, agentId: AgentId?): String {
        return if (agentId != null && !dryRun) {
            var result = ""
            agentRepository.checkpointAndRestoreConfig(agentId) {
                result = remote.recompileConversation(id, dryRun, agentId)
            }
            result
        } else {
            remote.recompileConversation(id, dryRun, agentId)
        }
    }

    override suspend fun forkConversation(id: ConversationId, agentId: AgentId): Conversation {
        val conversation = remote.forkConversation(id, agentId)
        upsertCachedConversation(conversation, markAgentFresh = true)
        return conversation
    }

    private suspend fun snapshotForAgent(agentId: AgentId): List<Conversation> {
        return getCachedConversations(agentId).ifEmpty {
            localCache?.invoke()?.getForAgentOnce(agentId) ?: emptyList()
        }
    }

    private suspend fun upsertCachedConversation(conversation: Conversation, markAgentFresh: Boolean = false) {
        localCache?.invoke()?.upsert(conversation)
        val current = snapshotForAgent(conversation.agentId)
        val updated = listOf(conversation) + current.filterNot { it.id == conversation.id }
        updateMemoryCache(conversation.agentId, updated)
        if (markAgentFresh) {
            val refreshedAt = nowMillis()
            lastRefreshAtMillisByAgent[conversation.agentId] = refreshedAt
            localCache?.invoke()?.upsertRefreshState(conversation.agentId, refreshedAt)
        }
    }

    private suspend fun writeAgentConversations(
        agentId: AgentId,
        conversations: List<Conversation>,
        refreshedAtMillis: Long,
    ) {
        localCache?.invoke()?.replaceForAgent(agentId, conversations, refreshedAtMillis)
        updateMemoryCache(agentId, conversations)
        lastRefreshAtMillisByAgent[agentId] = refreshedAtMillis
    }

    private fun updateMemoryCache(agentId: AgentId, conversations: List<Conversation>) {
        _conversationsByAgent.update { current ->
            current.toMutableMap().apply { put(agentId, conversations) }
        }
    }

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
