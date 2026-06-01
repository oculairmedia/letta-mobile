package com.letta.mobile.data.repository

import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.ConversationEntity
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationCountEstimate
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.paging.ConversationPagingSource
import com.letta.mobile.data.repository.api.IAllConversationsRepository
import com.letta.mobile.data.session.BackendScopedCache
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
import javax.inject.Inject

internal fun defaultAllConversationsScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

open class AllConversationsRepository(
    private val conversationApi: ConversationApi,
    private val conversationDao: ConversationDao? = null,
    private val repositoryScope: CoroutineScope,
) : IAllConversationsRepository, BackendScopedCache {
    /** Hilt-friendly constructor — uses [defaultAllConversationsScope]. */
    @Inject
    constructor(
        conversationApi: ConversationApi,
        conversationDao: ConversationDao? = null,
    ) : this(conversationApi, conversationDao, defaultAllConversationsScope())

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
                val cached = conversationDao?.getAllOnce()?.map { it.toConversation() }.orEmpty()
                if (cached.isNotEmpty()) {
                    _conversations.value = cached
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load cached conversations", e)
            }
        }
    }

    override open fun getConversationsPaged(
        agentId: AgentId?,
        archiveStatus: String?,
        summarySearch: String?,
    ): Flow<PagingData<Conversation>> {
        return Pager(
            config = PagingConfig(
                pageSize = ConversationPagingSource.PAGE_SIZE,
                enablePlaceholders = false,
                initialLoadSize = ConversationPagingSource.PAGE_SIZE,
            ),
            pagingSourceFactory = {
                ConversationPagingSource(
                    conversationApi = conversationApi,
                    agentId = agentId,
                    archiveStatus = archiveStatus,
                    summarySearch = summarySearch,
                    order = "desc",
                    orderBy = "last_message_at",
                )
            },
        ).flow
    }

    override open suspend fun loadNextPage() {
        if (!_hasMore.value) return

        val newConversations = fetchPage(after = currentCursor)
        applyLoadedPage(newConversations)
    }

    override open suspend fun refresh() = refreshMutex.withLock {
        refreshLocked()
    }

    override suspend fun clearForBackendSwitch() {
        refreshMutex.withLock {
            currentCursor = null
            lastRefreshAtMillis = 0L
            hasLoadedAtLeastOnce = false
            _conversations.value = emptyList()
            _hasMore.value = true
            // Propagate DAO failure. See AgentRepository.clearForBackendSwitch
            // for the rationale; same invariant — stale cached conversations
            // from the previous backend visible after switch is a cross-
            // backend leak we'd rather surface than silently log.
            conversationDao?.deleteAll()
            conversationDao?.deleteAllRefreshStates()
        }
    }

    private suspend fun refreshLocked() {
        val firstPage = fetchPage(after = null)
        currentCursor = null
        hasLoadedAtLeastOnce = false
        _conversations.update { emptyList() }
        _hasMore.update { true }
        applyLoadedPage(firstPage)
        lastRefreshAtMillis = System.currentTimeMillis()
    }

    override open fun hasFreshConversations(maxAgeMs: Long): Boolean {
        return hasLoadedAtLeastOnce && System.currentTimeMillis() - lastRefreshAtMillis <= maxAgeMs
    }

    override open suspend fun refreshIfStale(maxAgeMs: Long): Boolean = refreshMutex.withLock {
        if (hasFreshConversations(maxAgeMs)) return@withLock false
        refreshLocked()
        true
    }

    override open fun handleOptimisticUpdate(conversation: Conversation) {
        _conversations.update { current ->
            val index = current.indexOfFirst { it.id == conversation.id }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = conversation }
            } else {
                listOf(conversation) + current
            }
        }
        repositoryScope.launch {
            conversationDao?.upsert(ConversationEntity.fromConversation(conversation))
        }
    }

    override open fun handleOptimisticDelete(conversationId: ConversationId) {
        _conversations.update { current -> current.filter { it.id != conversationId } }
        repositoryScope.launch {
            conversationDao?.delete(conversationId.value)
        }
    }

    /**
     * Lightweight loaded-page count estimate.
     *
     * Letta API doesn't have a /v1/conversations/count endpoint. Do not make a
     * dedicated network request just to compute a dashboard count; refresh or
     * page the list first, then display [ConversationCountEstimate.count] as an
     * exact count when [ConversationCountEstimate.isApproximate] is false or as a
     * lower bound (for example, "50+") when more pages are available.
     */
    override open fun loadedCountEstimate(): ConversationCountEstimate? {
        if (!hasLoadedAtLeastOnce && _conversations.value.isEmpty()) return null
        return ConversationCountEstimate(
            count = _conversations.value.size,
            isApproximate = _hasMore.value,
        )
    }

    /**
     * Legacy compatibility shim. Prefer [loadedCountEstimate] so callers must
     * decide how to display approximate/unknown counts. This method intentionally
     * performs no network I/O.
     */
    @Deprecated("Use loadedCountEstimate() and render approximate/unknown states explicitly.")
    override open suspend fun countConversations(): Int {
        return loadedCountEstimate()?.count ?: 0
    }

    companion object {
        private const val PAGE_SIZE = 50
        private const val TAG = "AllConversationsRepo"
    }

    private suspend fun fetchPage(after: String?): List<Conversation> {
        return conversationApi.listConversations(
            limit = PAGE_SIZE,
            after = after,
        )
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
        conversationDao?.upsertAll(conversations.map { ConversationEntity.fromConversation(it) })
    }
}
