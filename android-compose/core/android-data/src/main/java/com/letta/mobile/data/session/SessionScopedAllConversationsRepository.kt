package com.letta.mobile.data.session

import androidx.paging.PagingData
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationCountEstimate
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.IAllConversationsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull

// The singleton repository owns this scope and cancels it explicitly in close().
@Suppress("NoDetachedCoroutineLifecycle")
internal fun defaultSessionScopedAllConversationsRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedAllConversationsRepository internal constructor(
    private val sessionManager: SessionManager,
    private val proxyScope: CoroutineScope,
) : IAllConversationsRepository, BackendScopedCache {
    @Inject
    constructor(
        sessionManager: SessionManager,
    ) : this(
        sessionManager = sessionManager,
        proxyScope = defaultSessionScopedAllConversationsRepositoryScope(),
    )

    private val _conversations = MutableStateFlow(sessionManager.current.allConversationsRepository.conversations.value)
    override val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _hasMore = MutableStateFlow(sessionManager.current.allConversationsRepository.hasMore.value)
    override val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    init {
        sessionManager.currentGraph
            .flatMapLatest { it.allConversationsRepository.conversations }
            .onEach { _conversations.value = it }
            .launchIn(proxyScope)
        sessionManager.currentGraph
            .flatMapLatest { it.allConversationsRepository.hasMore }
            .onEach { _hasMore.value = it }
            .launchIn(proxyScope)
    }

    private val current: IAllConversationsRepository
        get() = sessionManager.current.allConversationsRepository

    @Suppress("UNCHECKED_CAST")
    override fun getConversationsPaged(
        agentId: AgentId?,
        archiveStatus: String?,
        summarySearch: String?,
    ): Flow<PagingData<Conversation>> = sessionManager.currentGraph
        .flatMapLatest { it.allConversationsRepository.getConversationsPaged(agentId, archiveStatus, summarySearch) }

    override suspend fun loadNextPage(): Unit = sessionManager.withCurrentSession { it.allConversationsRepository.loadNextPage() }

    override suspend fun refresh() = withCurrentSessionAndRetryOnSwitch { graph ->
        graph.allConversationsRepository.refresh()
        syncProxyState(graph)
    }
    override suspend fun clearForBackendSwitch() {
        _conversations.value = emptyList()
        _hasMore.value = true
        sessionManager.current.allConversationsRepository.clearForBackendSwitch()
    }

    override fun hasFreshConversations(maxAgeMs: Long): Boolean = current.hasFreshConversations(maxAgeMs)

    override suspend fun refreshIfStale(maxAgeMs: Long): Boolean = withCurrentSessionAndRetryOnSwitch { graph ->
        val refreshed = graph.allConversationsRepository.refreshIfStale(maxAgeMs)
        syncProxyState(graph)
        refreshed
    }

    // The proxy StateFlows are fed by an async collection in [proxyScope];
    // callers that read [conversations].value right after a refresh raced it
    // and saw the pre-refresh snapshot (empty list on first local-runtime
    // load). Copy the session repository's state synchronously on refresh so
    // refresh-then-read is always consistent.
    private fun syncProxyState(graph: SessionGraph) {
        _conversations.value = graph.allConversationsRepository.conversations.value
        _hasMore.value = graph.allConversationsRepository.hasMore.value
    }

    /**
     * letta-mobile-xzoy3: refresh/refreshIfStale run on the instant
     * `activeConfigChanges` emits, racing SessionManager's async graph
     * rebuild (separate scope). The rebuild can land mid-op
     * (`withCurrentSession` post-check -> CancellationException) or 18ms
     * late (stale-transport IllegalStateException); both were swallowed by
     * the caller's catch-all -> silent no-op. Refetch is idempotent, so
     * retry against the now-current graph, bounded.
     */
    private suspend fun <T> withCurrentSessionAndRetryOnSwitch(block: suspend (SessionGraph) -> T): T {
        var attempts = 0
        while (attempts < MAX_SESSION_SWITCH_RETRIES) {
            val graphAtStart = sessionManager.current
            try {
                return sessionManager.withCurrentSession(block)
            } catch (e: CancellationException) {
                if (e.message != SESSION_SWITCHED_MESSAGE) throw e
                attempts++
            } catch (e: IllegalStateException) {
                attempts++
                val next = withTimeoutOrNull(SESSION_REBUILD_WAIT_MS) {
                    sessionManager.currentGraph.first { it !== graphAtStart }
                }
                if (next == null) throw e
            }
        }
        throw IllegalStateException(
            "Session graph kept switching; refresh abandoned after $MAX_SESSION_SWITCH_RETRIES attempts",
        )
    }

    override fun handleOptimisticUpdate(conversation: Conversation): Unit = current.handleOptimisticUpdate(conversation)
    override fun handleOptimisticDelete(conversationId: ConversationId): Unit = current.handleOptimisticDelete(conversationId)
    override fun loadedCountEstimate(): ConversationCountEstimate? = current.loadedCountEstimate()

    @Deprecated("Use loadedCountEstimate() and render approximate/unknown states explicitly.")
    @Suppress("DEPRECATION")
    override suspend fun countConversations(): Int = sessionManager.withCurrentSession { it.allConversationsRepository.countConversations() }

    fun close() { proxyScope.cancel() }

    private companion object {
        const val MAX_SESSION_SWITCH_RETRIES = 3
        const val SESSION_SWITCHED_MESSAGE = "Session switched during operation"
        const val SESSION_REBUILD_WAIT_MS = 300L
    }
}
