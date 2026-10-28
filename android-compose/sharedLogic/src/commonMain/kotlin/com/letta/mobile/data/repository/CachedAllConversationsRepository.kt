package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.util.AgentConversationFleet
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationCountEstimate
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.AllConversationsLocalCache
import com.letta.mobile.data.repository.api.ConversationRemoteSource
import com.letta.mobile.data.repository.api.IAllConversationsRepository
import com.letta.mobile.data.session.BackendScopedCache
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.util.Telemetry
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Suppress("NoDetachedCoroutineLifecycle")
fun defaultCachedAllConversationsRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Phase 5b.2: platform-neutral cached all-conversations repository: the fleet's conversations, read
 * agent by agent through the shared [AgentConversationFleet] (each [roster] agent's own pages), never
 * as one window across every agent.
 */
open class CachedAllConversationsRepository(
    /** The agents whose conversations the list carries, read when the list is (re)loaded. */
    private val roster: suspend () -> List<Agent>,
    private val remote: RemoteRoute<ConversationRemoteSource>,
    private val localCache: (() -> AllConversationsLocalCache)? = null,
    private val repositoryScope: CoroutineScope = defaultCachedAllConversationsRepositoryScope(),
    private val irohConversationListSource: IrohAdminRpcConversationListSource? = null,
    /**
     * When given, the list follows Meridian's `conversation_updated` pushes and
     * re-reads after a reconnect, so conversations created on another device appear without a
     * restart.
     */
    transport: IChannelTransport? = null,
) : IAllConversationsRepository, BackendScopedCache {
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    override val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    override val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val refreshMutex = Mutex()
    private val fleet = AgentConversationFleet(::readAgentPage) { agent, failure ->
        Telemetry.event(
            TAG, "agent_read_failed",
            "agentId" to agent.value,
            "error" to failure.toString(),
            level = Telemetry.Level.WARN,
        )
    }
    private var lastRefreshAtMillis: Long = 0L
    private var hasLoadedAtLeastOnce: Boolean = false

    init {
        repositoryScope.launch {
            try {
                val cached = localCache?.invoke()?.getAllOnce().orEmpty()
                // A list already read from the backend wins over a cache that arrived late.
                if (cached.isNotEmpty() && !hasLoadedAtLeastOnce) {
                    _conversations.value = cached
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Telemetry.event(
                    TAG,
                    "Failed to load cached conversations",
                    "error" to (e.message ?: e.toString()),
                    level = Telemetry.Level.WARN,
                )
            }
        }
        transport?.let { channelTransport ->
            repositoryScope.launch { observeConversationInvalidations(channelTransport.conversationChanges(), ::applyPush) }
            repositoryScope.launch { observeReconnectRefresh(channelTransport) { refreshAfterPush("reconnect") } }
        }
    }

    /** A legacy push without an agent keeps its row refetch; scoped pushes list each owning agent once. */
    private suspend fun applyPush(dirty: Map<ConversationId, AgentId?>) {
        if (!hasLoadedAtLeastOnce) return
        try {
            refreshPushedLocked(dirty)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unsupported: UnsupportedOperationException) {
            refreshAfterPush("unsupported")
        } catch (e: Exception) {
            Telemetry.event(TAG, "conversation_updated.refetch_failed", "error" to (e.message ?: e.toString()), level = Telemetry.Level.WARN)
        }
    }

    private suspend fun refreshPushedLocked(dirty: Map<ConversationId, AgentId?>) = refreshMutex.withLock {
        if (!hasLoadedAtLeastOnce) return@withLock
        val source = irohConversationListSource?.takeIf { it.shouldUseIroh() }
        val legacy = dirty.keys.singleOrNull()?.takeIf { dirty[it] == null }
        if (legacy != null && source != null) {
            val fresh = source.getConversation(legacy)
            fleet.record(fresh)
            handleOptimisticUpdate(fresh)
        } else {
            val listed = fleet.refreshChanged(dirty) { id -> source?.getConversation(id) }
            if (listed == null) refreshLocked() else publish(listed)
        }
    }

    /** Only a list that has loaded is refreshed: an unopened list loads fresh when first shown. */
    private suspend fun refreshAfterPush(trigger: String) {
        if (!hasLoadedAtLeastOnce) return
        try {
            refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Telemetry.event(TAG, "conversation_updated.refresh_failed", "trigger" to trigger, "error" to (e.message ?: e.toString()), level = Telemetry.Level.WARN)
        }
    }

    override suspend fun loadNextPage() = refreshMutex.withLock {
        if (!_hasMore.value) return@withLock
        if (!hasLoadedAtLeastOnce) return@withLock refreshLocked()
        val listed = fleet.loadMore()
        // Rows the list gained meanwhile (a push, an optimistic write) stay.
        publish(listed + _conversations.value.filterNot { row -> listed.any { it.id == row.id } })
    }

    override suspend fun refresh() = refreshMutex.withLock {
        refreshLocked()
    }

    override suspend fun clearForBackendSwitch() {
        refreshMutex.withLock {
            lastRefreshAtMillis = 0L
            hasLoadedAtLeastOnce = false
            _conversations.value = emptyList()
            _hasMore.value = true
            localCache?.invoke()?.deleteAll()
            localCache?.invoke()?.deleteAllRefreshStates()
        }
    }

    override fun hasFreshConversations(maxAgeMs: Long): Boolean {
        return hasLoadedAtLeastOnce && nowMillis() - lastRefreshAtMillis <= maxAgeMs
    }

    override suspend fun refreshIfStale(maxAgeMs: Long): Boolean = refreshMutex.withLock {
        if (hasFreshConversations(maxAgeMs)) return@withLock false
        refreshLocked()
        true
    }

    override fun handleOptimisticUpdate(conversation: Conversation) {
        _conversations.update { current ->
            val index = current.indexOfFirst { it.id == conversation.id }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = conversation }
            } else {
                listOf(conversation) + current
            }
        }
        repositoryScope.launch {
            localCache?.invoke()?.upsert(conversation)
        }
    }

    override fun handleOptimisticDelete(conversationId: ConversationId) {
        _conversations.update { current -> current.filter { it.id != conversationId } }
        repositoryScope.launch {
            localCache?.invoke()?.delete(conversationId.value)
        }
    }

    override fun loadedCountEstimate(): ConversationCountEstimate? {
        if (!hasLoadedAtLeastOnce && _conversations.value.isEmpty()) return null
        return ConversationCountEstimate(
            count = _conversations.value.size,
            isApproximate = _hasMore.value,
        )
    }

    companion object {
        private const val TAG = "AllConversationsRepo"

        /** The list filters archived rows itself (its Archived tab), so it reads them too. */
        private const val ARCHIVE_STATUS_ALL = "all"
    }

    private suspend fun refreshLocked() {
        publish(fleet.refresh(roster()))
        lastRefreshAtMillis = nowMillis()
    }

    /** One page of one agent's own conversations, from the session's channel or the backend seam. */
    private suspend fun readAgentPage(agent: AgentId, after: ConversationId?, limit: Int): List<Conversation> {
        val irohSource = irohConversationListSource
        return if (irohSource?.shouldUseIroh() == true) {
            irohSource.listAgentConversations(agent, after, limit)
        } else {
            remote.source().listConversations(
                agentId = agent,
                limit = limit,
                after = after?.value,
                archiveStatus = ARCHIVE_STATUS_ALL,
                summarySearch = null,
                order = "desc",
                orderBy = "last_message_at",
            )
        }
    }

    private suspend fun publish(listed: List<Conversation>) {
        hasLoadedAtLeastOnce = true
        _conversations.value = listed
        _hasMore.value = fleet.hasMore
        if (listed.isNotEmpty()) cacheConversations(listed)
    }

    private suspend fun cacheConversations(conversations: List<Conversation>) {
        localCache?.invoke()?.upsertAll(conversations)
    }

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
