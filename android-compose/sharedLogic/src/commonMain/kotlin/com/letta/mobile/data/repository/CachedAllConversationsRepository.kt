package com.letta.mobile.data.repository

import com.letta.mobile.util.attr
import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.chat.conversations.listing.RecentConversationListing
import com.letta.mobile.data.chat.runtime.AgentConversationPage
import com.letta.mobile.data.chat.runtime.ChatConversationStore
import com.letta.mobile.util.AgentConversationFleetPaging
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationCountEstimate
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ConversationRecency
import com.letta.mobile.data.repository.api.AllConversationsLocalCache
import com.letta.mobile.data.repository.api.ConversationRemoteSource
import com.letta.mobile.data.repository.api.IAllConversationsRepository
import com.letta.mobile.data.session.BackendScopedCache
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.util.Telemetry
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
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
 * Phase 5b.2: platform-neutral cached all-conversations repository, read recents-first through the
 * shared [RecentConversationListing] that web and desktop use: one visible recents page plus the
 * pinned agents' (and already listed agents') own pages, never one read per roster agent. A host
 * without visible recents keeps the per-agent read of every visible [roster] agent.
 */
open class CachedAllConversationsRepository(
    /** The agents whose conversations the list carries: visibility for recents, every agent on a legacy host. */
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
    /** Pinned agents, whose own first pages load with every refresh as on web and desktop. */
    private val pinnedAgents: suspend () -> Set<AgentId> = { emptySet() },
) : IAllConversationsRepository, BackendScopedCache {
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    override val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    override val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val refreshMutex = Mutex()
    private val store = RepositoryConversationStore()

    /** Replaced on a backend switch, so the next host is asked for visible recents again. */
    private var listing = newListing()

    /** The roster the list was last read with; a push folds rows in by it without re-reading the roster. */
    private var listedRoster: List<Agent> = emptyList()
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
                    "error" attr (e.message ?: e.toString()),
                    level = Telemetry.Level.WARN,
                )
            }
        }
        transport?.let { channelTransport ->
            repositoryScope.launch {
                observeConversationInvalidations(channelTransport.conversationChanges(), PUSH_COALESCE_WINDOW, ::applyPush)
            }
            repositoryScope.launch { observeReconnectRefresh(channelTransport) { refreshAfterPush("reconnect") } }
        }
    }

    /**
     * A push re-reads only the conversations it names (`conversation.get`), never their agents'
     * pages: a turn's pushes used to re-list its agent every few seconds. A
     * row that cannot be read alone falls back to the shared owner/roster reconciliation.
     */
    private suspend fun applyPush(dirty: Map<ConversationId, AgentId?>) {
        if (!hasLoadedAtLeastOnce) return
        try {
            refreshPushedLocked(dirty)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: UnsupportedOperationException) {
            refreshAfterPush("unsupported")
        } catch (e: Exception) {
            Telemetry.event(
                TAG,
                "conversation_updated.refetch_failed",
                "error" attr (e.message ?: e.toString()),
                level = Telemetry.Level.WARN,
            )
        }
    }

    private suspend fun refreshPushedLocked(dirty: Map<ConversationId, AgentId?>) = refreshMutex.withLock {
        if (!hasLoadedAtLeastOnce) return@withLock
        val source = irohConversationListSource?.takeIf { it.shouldUseIroh() }
        val fresh = source?.let { readEach(it, dirty.keys) }.orEmpty()
        if (fresh.isNotEmpty()) publishRows(fresh)
        val unread = dirty.filterKeys { id -> fresh.none { it.id == id } }
        if (unread.isEmpty()) return@withLock
        publish(listing.changed(unread, roster()))
    }

    /** Each conversation read by itself; one that cannot be read (gone, refused) is left out. */
    private suspend fun readEach(source: IrohAdminRpcConversationListSource, ids: Set<ConversationId>): List<Conversation> =
        ids.mapNotNull { id ->
            try {
                source.getConversation(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

    /** Folds re-read rows into the listing and the published list, as a list read would; no agent is listed. */
    private suspend fun publishRows(fresh: List<Conversation>) {
        val listed = listing.record(fresh, listedRoster)
        val ids = fresh.mapTo(mutableSetOf()) { it.id }
        // Rows the list gained meanwhile (an optimistic write) stay; the re-read rows are as listed.
        _conversations.update { current ->
            val listedIds = listed.mapTo(mutableSetOf()) { it.id }
            (listed + current.filterNot { it.id in ids || it.id in listedIds }).sortedWith(ConversationRecency.newestFirst)
        }
        val kept = listed.filter { it.id in ids }
        if (kept.isNotEmpty()) cacheConversations(kept)
    }

    /** Only a list that has loaded is refreshed: an unopened list loads fresh when first shown. */
    private suspend fun refreshAfterPush(trigger: String) {
        if (!hasLoadedAtLeastOnce) return
        try {
            refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Telemetry.event(
                TAG,
                "conversation_updated.refresh_failed",
                "trigger" attr trigger,
                "error" attr (e.message ?: e.toString()),
                level = Telemetry.Level.WARN,
            )
        }
    }

    override suspend fun loadNextPage() = refreshMutex.withLock {
        if (!_hasMore.value) return@withLock
        if (!hasLoadedAtLeastOnce) return@withLock refreshLocked()
        val listed = listing.loadMore()
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
            listing = newListing()
            listedRoster = emptyList()
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

        /** A legacy host's agents are each read a page at a time, paged on by [loadNextPage]. */
        private val LEGACY_PAGING = AgentConversationFleetPaging()

        /** Pushes within this window of the first are re-read together. */
        private val PUSH_COALESCE_WINDOW = 500.milliseconds
    }

    private fun newListing() = RecentConversationListing(store, LEGACY_PAGING)

    private suspend fun refreshLocked() {
        val agents = roster().also { listedRoster = it }
        publish(listing.refresh(agents, pinnedAgents()) { recents -> _conversations.value = recents })
        lastRefreshAtMillis = nowMillis()
    }

    /**
     * The repository's sources as the shared listing reads them: agent pages from the session channel
     * or the backend seam; visible recents and single rows only over the channel (the seam has neither,
     * so the listing keeps its per-agent reads there).
     */
    private inner class RepositoryConversationStore : ChatConversationStore {
        override suspend fun listAgentConversations(page: AgentConversationPage): List<Conversation> =
            readAgentPage(page.agent, page.after, page.limit)

        override suspend fun listRecentConversations(limit: Int, after: ConversationId?): List<Conversation> =
            channelSource()?.listRecentConversations(limit, after)
                ?: throw UnsupportedOperationException("Visible recents need the session channel")

        override suspend fun getConversation(id: ConversationId): Conversation =
            channelSource()?.getConversation(id)
                ?: throw UnsupportedOperationException("Single conversation reads need the session channel")

        private fun channelSource() = irohConversationListSource?.takeIf { it.shouldUseIroh() }
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
        _hasMore.value = listing.hasMore
        if (listed.isNotEmpty()) cacheConversations(listed)
    }

    private suspend fun cacheConversations(conversations: List<Conversation>) {
        localCache?.invoke()?.upsertAll(conversations)
    }

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
