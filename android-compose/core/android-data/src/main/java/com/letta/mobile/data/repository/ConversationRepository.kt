package com.letta.mobile.data.repository

import android.util.Log
import com.letta.mobile.data.backend.noAppServerRoute
import com.letta.mobile.data.repository.api.ConversationCrudRemoteSource
import com.letta.mobile.data.chat.branch.BranchUnavailableException
import com.letta.mobile.data.chat.branch.ConversationForkRequest
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.ConversationEntity
import com.letta.mobile.data.local.ConversationRefreshEntity
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentRuntimeBinding
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationCreateParams
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ConversationUpdateParams
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.LocalRuntimeConversationSource
import com.letta.mobile.data.session.BackendScopedCache
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dagger.Lazy
import java.util.concurrent.ConcurrentHashMap

// letta-mobile-g2ff0: conversationDao is dagger.Lazy<ConversationDao> so Room init
// happens lazily on the first dao.get() call (inside repositoryScope.launch
// {} blocks), not synchronously on the main thread during Hilt graph resolution.
// (dagger.Lazy, not javax.inject.Provider — Hilt's KSP processor rejects
// @Provides methods returning framework types like Provider.)
open class ConversationRepository(
    private val remote: ConversationCrudRemoteSource = noAppServerRoute(),
    private val agentRepository: IAgentRepository,
    private val conversationDao: Lazy<ConversationDao>,
    repositoryScope: CoroutineScope,
    private val localConversationSource: LocalRuntimeConversationSource? = null,
    private val settingsRepository: ISettingsRepository? = null,
    private val irohConversationListSource: IrohAdminRpcConversationListSource? = null,
    /**
     * letta-mobile-xj85j: when given, an agent's loaded conversation list follows Meridian's
     * `conversation_updated` pushes, so the in-chat picker and drawer show a conversation another
     * device created or changed without reopening.
     */
    transport: IChannelTransport? = null,
) : IConversationRepository, BackendScopedCache {
    private val _conversationsByAgent = MutableStateFlow<Map<AgentId, List<Conversation>>>(emptyMap())
    /** Single-flights network refreshes; held across the list request. Taken before [cacheMutex], never after. */
    private val refreshMutex = Mutex()

    /**
     * letta-mobile-by4vm.16: guards every write to the cached lists (Room + memory) and the freshness
     * map, so a refresh, a push, an optimistic edit and its rollback never interleave half-applied.
     * Held only for local writes, never across a network call.
     */
    private val cacheMutex = Mutex()

    /** Written under [cacheMutex]; concurrent so [hasFreshConversations] can read it lock-free. */
    private val lastRefreshAtMillisByAgent = ConcurrentHashMap<AgentId, Long>()

    init {
        repositoryScope.launch {
            try {
                val cached = conversationDao.get().getAllOnce().map { it.toConversation() }.groupBy { it.agentId }
                val refreshStates = conversationDao.get().getAllRefreshStatesOnce()
                // letta-mobile-by4vm.16: merge, never assign — a refresh or push that finished while
                // this read was in flight holds newer rows and a newer stamp than this disk snapshot.
                cacheMutex.withLock {
                    _conversationsByAgent.update { current -> cached + current }
                    refreshStates.forEach { state ->
                        lastRefreshAtMillisByAgent.putIfAbsent(AgentId(state.agentId), state.lastRefreshAtMillis)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load cached conversations", e)
            }
        }
        transport?.let { channelTransport ->
            repositoryScope.launch { observeConversationUpdates(channelTransport) { frame -> applyPush(frame) } }
        }
    }

    /**
     * Refetches the ONE conversation a push names and upserts it into its agent's cached list; the
     * Room-backed [getConversations] flow then re-emits in place. Agents whose list was never loaded
     * here are skipped (they load fresh when opened), and a failed fetch waits for the next refresh.
     */
    private suspend fun applyPush(frame: ServerFrame.ConversationUpdated) {
        val source = irohConversationListSource?.takeIf { it.shouldUseIroh() } ?: return
        val namedAgent = frame.agentId?.takeIf { it.isNotBlank() }?.let(::AgentId)
        if (namedAgent != null && !hasLoadedAgent(namedAgent)) return
        val fresh = refetchPushedConversation(source, frame.conversationId) ?: return
        if (!hasLoadedAgent(fresh.agentId)) return
        cacheMutex.withLock {
            conversationDao.get().upsert(ConversationEntity.fromConversation(fresh))
            val current = getCachedConversations(fresh.agentId)
            val index = current.indexOfFirst { it.id == fresh.id }
            updateMemoryCache(
                fresh.agentId,
                if (index >= 0) current.toMutableList().apply { this[index] = fresh } else listOf(fresh) + current,
            )
        }
    }

    private suspend fun refetchPushedConversation(
        source: IrohAdminRpcConversationListSource,
        conversationId: String,
    ): Conversation? = try {
        source.getConversation(ConversationId(conversationId))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        Log.w(TAG, "conversation_updated refetch failed", e)
        null
    }

    /** Whether this agent's list is held here, in memory or on disk (the memory copy may still be loading). */
    private suspend fun hasLoadedAgent(agentId: AgentId): Boolean =
        lastRefreshAtMillisByAgent.containsKey(agentId) || snapshotForAgent(agentId).isNotEmpty()

    override fun getConversations(agentId: AgentId): Flow<List<Conversation>> {
        return conversationDao.get().observeForAgent(agentId.value).map { rows ->
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
            cacheMutex.withLock {
                _conversationsByAgent.value = emptyMap()
                lastRefreshAtMillisByAgent.clear()
                // Propagate DAO failure. See AgentRepository.clearForBackendSwitch
                // for the rationale.
                conversationDao.get().deleteAll()
                conversationDao.get().deleteAllRefreshStates()
            }
        }
    }

    private suspend fun refreshConversationsLocked(agentId: AgentId) {
        val irohSource = irohConversationListSource
        val conversations = if (irohSource?.shouldUseIroh() == true) {
            irohSource.listConversations(agentId = agentId)
        } else {
            remote.listConversations(
                agentId = agentId,
                limit = null,
                after = null,
                archiveStatus = null,
                summarySearch = null,
                order = null,
                orderBy = null,
            )
        }
        val refreshedAt = System.currentTimeMillis()
        writeAgentConversations(agentId, conversations, refreshedAt)
    }

    override fun getCachedConversations(agentId: AgentId): List<Conversation> = _conversationsByAgent.value[agentId] ?: emptyList()

    // letta-mobile-i9h61.3.2: agent-scoped list for tap-to-navigate.
    // The picker calls this on the OTHER agent (not the current one),
    // so caching is not meaningful — each tap fetches fresh. Default
    // IConversationRepository impl returns empty, so any gateway
    // without the agent-scoped surface degrades cleanly.
    override suspend fun listConversationsForAgent(
        agentId: AgentId,
        limit: Int,
    ): List<Conversation> = refreshMutex.withLock {
        val irohSource = irohConversationListSource
        if (irohSource?.shouldUseIroh() != true) {
            return@withLock emptyList()
        }
        irohSource.listConversationsForAgent(agentId, limit)
    }

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
            val irohSource = irohConversationListSource
            val fetched = if (irohSource?.shouldUseIroh() == true) {
                irohSource.getConversation(id)
            } else {
                remote.getConversation(id)
            }
            fetched.also { conversation -> upsertCachedConversation(conversation) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            conversationDao.get().getByIdOnce(id.value)?.toConversation() ?: throw e
        }
    }

    override suspend fun createConversation(agentId: AgentId, summary: String?): Conversation {
        val irohSource = irohConversationListSource
        val conversation = when {
            localConversationSource != null && AgentRuntimeBinding.isLocalRuntime(settingsRepository?.activeConfig?.value) ->
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

    // letta-mobile-by4vm.16: optimistic edits touch only their one row and never stamp the list
    // fresh; a failure compensates that row alone, so a refresh or push landing meanwhile survives.
    override suspend fun deleteConversation(id: ConversationId, agentId: AgentId) {
        val removed = removeCachedConversation(agentId, id)
        try {
            val irohSource = irohConversationListSource
            if (irohSource?.shouldUseIroh() == true) {
                irohSource.deleteConversation(id)
            } else {
                remote.deleteConversation(id)
            }
        } catch (e: Exception) {
            if (removed != null) restoreCachedConversation(agentId, removed)
            throw e
        }
    }

    override suspend fun updateConversation(id: ConversationId, agentId: AgentId, summary: String) {
        val swap = swapCachedConversation(agentId, id) { it.copy(summary = summary) } ?: return
        try {
            val irohSource = irohConversationListSource
            val updated = if (irohSource?.shouldUseIroh() == true) {
                irohSource.updateConversation(id, summary)
            } else {
                remote.updateConversation(id, ConversationUpdateParams(summary = summary))
            }
            swapCachedConversation(agentId, id) { updated }
        } catch (e: Exception) {
            revertCachedConversation(agentId, swap)
            throw e
        }
    }

    override suspend fun setConversationArchived(id: ConversationId, agentId: AgentId, archived: Boolean) {
        // The write must reach the server whether or not this agent's list is cached here: the
        // all-conversations screen archives rows whose agent list was never opened, and returning
        // early left those archived only on screen until the next refresh brought them back.
        val swap = swapCachedConversation(agentId, id) { it.copy(archived = archived) }
        try {
            val irohSource = irohConversationListSource
            val updated = if (irohSource?.shouldUseIroh() == true) {
                irohSource.setConversationArchived(id, archived)
            } else {
                remote.updateConversation(id, ConversationUpdateParams(archived = archived))
            }
            if (swap != null) swapCachedConversation(agentId, id) { updated }
        } catch (e: Exception) {
            if (swap != null) revertCachedConversation(agentId, swap)
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

    /** The embedded runtime (letta-code 0.26.1) has no `conversation_fork`; every other backend forks. */
    override fun canForkConversations(): Boolean =
        localConversationSource == null || !AgentRuntimeBinding.isLocalRuntime(settingsRepository?.activeConfig?.value)

    override suspend fun forkConversation(id: ConversationId, agentId: AgentId): Conversation =
        forkConversation(id, agentId, throughMessageId = null)

    /**
     * letta-mobile-bzvro.15: an App Server / Iroh backend forks through the host's
     * `conversation.fork` (App Server `conversation_fork`); only a REST backend calls `/fork`.
     */
    override suspend fun forkConversation(id: ConversationId, agentId: AgentId, throughMessageId: String?): Conversation {
        val request = ConversationForkRequest(id.value, agentId.value, throughMessageId)
        // letta-mobile-7mys7: the embedded runtime (letta-code 0.26.1) has no conversation_fork, and
        // its conversations are not on any REST host, so it must not fall through to `/fork`.
        if (!canForkConversations()) throw BranchUnavailableException(LOCAL_RUNTIME_CANNOT_FORK)
        val irohSource = irohConversationListSource
        val conversation = if (irohSource?.shouldUseIroh() == true) {
            irohSource.forkConversation(request)
        } else {
            remote.forkConversation(request)
        }
        upsertCachedConversation(conversation, markAgentFresh = true)
        return conversation
    }

    private suspend fun snapshotForAgent(agentId: AgentId): List<Conversation> {
        return getCachedConversations(agentId).ifEmpty {
            conversationDao.get().getForAgentOnce(agentId.value).map { it.toConversation() }
        }
    }

    private suspend fun upsertCachedConversation(conversation: Conversation, markAgentFresh: Boolean = false) =
        cacheMutex.withLock {
            conversationDao.get().upsert(ConversationEntity.fromConversation(conversation))
            val current = snapshotForAgent(conversation.agentId)
            val updated = listOf(conversation) + current.filterNot { it.id == conversation.id }
            updateMemoryCache(conversation.agentId, updated)
            if (markAgentFresh) {
                val refreshedAt = System.currentTimeMillis()
                lastRefreshAtMillisByAgent[conversation.agentId] = refreshedAt
                conversationDao.get().upsertRefreshState(
                    ConversationRefreshEntity(agentId = conversation.agentId.value, lastRefreshAtMillis = refreshedAt),
                )
            }
        }

    /** Drops row [id] from the agent's list; returns it with its position, or null when it was not cached. */
    private suspend fun removeCachedConversation(agentId: AgentId, id: ConversationId): IndexedValue<Conversation>? =
        cacheMutex.withLock {
            val current = snapshotForAgent(agentId)
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) return@withLock null
            conversationDao.get().delete(id.value)
            updateMemoryCache(agentId, current.filterNot { it.id == id })
            IndexedValue(index, current[index])
        }

    /** Compensates a failed delete: puts back the one removed row unless something re-added it meanwhile. */
    private suspend fun restoreCachedConversation(agentId: AgentId, removed: IndexedValue<Conversation>) =
        cacheMutex.withLock {
            val current = snapshotForAgent(agentId)
            if (current.any { it.id == removed.value.id }) return@withLock
            conversationDao.get().upsert(ConversationEntity.fromConversation(removed.value))
            updateMemoryCache(agentId, current.toMutableList().apply { add(removed.index.coerceAtMost(size), removed.value) })
        }

    /**
     * Replaces row [id] with `transform(row)` in place (a null result leaves it alone); returns the
     * (before, after) pair, or null when the row is not cached — it is never resurrected here.
     */
    private suspend fun swapCachedConversation(
        agentId: AgentId,
        id: ConversationId,
        transform: (Conversation) -> Conversation?,
    ): Pair<Conversation, Conversation>? = cacheMutex.withLock {
        val current = snapshotForAgent(agentId)
        val index = current.indexOfFirst { it.id == id }
        val after = current.getOrNull(index)?.let(transform) ?: return@withLock null
        conversationDao.get().upsert(ConversationEntity.fromConversation(after))
        updateMemoryCache(agentId, current.toMutableList().apply { this[index] = after })
        current[index] to after
    }

    /** Compensates a failed edit: restores the old row only while it still holds the optimistic value. */
    private suspend fun revertCachedConversation(agentId: AgentId, swap: Pair<Conversation, Conversation>) {
        val (before, optimistic) = swap
        swapCachedConversation(agentId, before.id) { row -> before.takeIf { row == optimistic } }
    }

    /** Stores a list the server just returned and stamps it fresh; only a real refresh calls this. */
    private suspend fun writeAgentConversations(
        agentId: AgentId,
        conversations: List<Conversation>,
        refreshedAtMillis: Long,
    ) = cacheMutex.withLock {
        conversationDao.get().replaceForAgent(
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
        const val LOCAL_RUNTIME_CANNOT_FORK =
            "The on-device runtime can't fork conversations; connect to a desktop or server host to fork."
    }
}
