package com.letta.mobile.data.repository

import androidx.paging.PagingData
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentRuntimeBinding
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationCountEstimate
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.AllConversationsLocalCache
import com.letta.mobile.data.repository.api.ConversationRemoteSource
import com.letta.mobile.data.repository.api.IAllConversationsRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.LocalRuntimeConversationSource
import com.letta.mobile.data.session.BackendScopedCache
import com.letta.mobile.util.Telemetry
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
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

/** Phase 5b.2: platform-neutral cached all-conversations repository. Paging stays in the Android binder. */
open class CachedAllConversationsRepository(
    private val remote: ConversationRemoteSource,
    private val localCache: (() -> AllConversationsLocalCache)? = null,
    private val repositoryScope: CoroutineScope = defaultCachedAllConversationsRepositoryScope(),
    private val localConversationSource: LocalRuntimeConversationSource? = null,
    private val settingsRepository: ISettingsRepository? = null,
    private val irohConversationListSource: IrohAdminRpcConversationListSource? = null,
) : IAllConversationsRepository, BackendScopedCache {
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    override val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    override val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val refreshMutex = Mutex()
    private var currentCursor: String? = null
    private var lastRefreshAtMillis: Long = 0L
    private var hasLoadedAtLeastOnce: Boolean = false

    init {
        repositoryScope.launch {
            try {
                val cached = localCache?.invoke()?.getAllOnce().orEmpty()
                if (cached.isNotEmpty()) {
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
    }

    override fun getConversationsPaged(
        agentId: AgentId?,
        archiveStatus: String?,
        summarySearch: String?,
    ): Flow<PagingData<Conversation>> {
        error("getConversationsPaged is Android-bound; override in AllConversationsRepository")
    }

    override suspend fun loadNextPage() = refreshMutex.withLock {
        if (!_hasMore.value) return@withLock
        val newConversations = fetchPage(after = currentCursor)
        applyLoadedPage(newConversations)
    }

    override suspend fun refresh() = refreshMutex.withLock {
        refreshLocked()
    }

    override suspend fun clearForBackendSwitch() {
        refreshMutex.withLock {
            currentCursor = null
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

    @Deprecated("Use loadedCountEstimate() and render approximate/unknown states explicitly.")
    override suspend fun countConversations(): Int = loadedCountEstimate()?.count ?: 0

    /** Exposed for the Android paging binder when Iroh list routing is active. */
    fun irohPageLoaderOrNull(): (suspend (
        agentId: AgentId?,
        limit: Int?,
        after: String?,
        archiveStatus: String?,
        summarySearch: String?,
        order: String?,
        orderBy: String?,
    ) -> List<Conversation>)? =
        irohConversationListSource?.takeIf { it.shouldUseIroh() }?.let { source ->
            { pageAgentId, limit, after, pageArchiveStatus, pageSummarySearch, order, orderBy ->
                source.listConversations(
                    agentId = pageAgentId,
                    limit = limit,
                    after = after,
                    archiveStatus = pageArchiveStatus,
                    summarySearch = pageSummarySearch,
                    order = order,
                    orderBy = orderBy,
                )
            }
        }

    companion object {
        internal const val PAGE_SIZE = 50
        private const val TAG = "AllConversationsRepo"
    }

    private suspend fun refreshLocked() {
        val localSource = localConversationSource
        if (localSource != null && AgentRuntimeBinding.isLocalRuntime(settingsRepository?.activeConfig?.value)) {
            val local = localSource.listConversations()
            Telemetry.event(TAG, "refreshLocked: local source returned ${local.size} conversations")
            _conversations.value = local
            _hasMore.value = false
            currentCursor = null
            hasLoadedAtLeastOnce = true
            lastRefreshAtMillis = nowMillis()
            return
        }
        val firstPage = fetchPage(after = null)
        currentCursor = null
        hasLoadedAtLeastOnce = false
        _conversations.update { emptyList() }
        _hasMore.update { true }
        applyLoadedPage(firstPage)
        lastRefreshAtMillis = nowMillis()
    }

    private suspend fun fetchPage(after: String?): List<Conversation> {
        val irohSource = irohConversationListSource
        return if (irohSource?.shouldUseIroh() == true) {
            irohSource.listConversations(
                agentId = null,
                limit = PAGE_SIZE,
                after = after,
                order = "desc",
                orderBy = "last_message_at",
            )
        } else {
            remote.listConversations(
                agentId = null,
                limit = PAGE_SIZE,
                after = after,
                archiveStatus = null,
                summarySearch = null,
                order = null,
                orderBy = null,
            )
        }
    }

    private suspend fun applyLoadedPage(newConversations: List<Conversation>) {
        hasLoadedAtLeastOnce = true
        if (newConversations.isEmpty() || newConversations.size < PAGE_SIZE) {
            _hasMore.update { false }
        }

        if (newConversations.isNotEmpty()) {
            _conversations.update { current ->
                val existingIds = current.map { it.id }.toSet()
                val deduped = newConversations.filter { it.id !in existingIds }
                current + deduped
            }
            cacheConversations(newConversations)
            currentCursor = newConversations.last().id.value
        }
    }

    private suspend fun cacheConversations(conversations: List<Conversation>) {
        localCache?.invoke()?.upsertAll(conversations)
    }

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
