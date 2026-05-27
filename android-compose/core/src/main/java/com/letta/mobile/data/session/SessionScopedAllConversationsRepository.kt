package com.letta.mobile.data.session

import androidx.paging.PagingData
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationCountEstimate
import com.letta.mobile.data.repository.api.IAllConversationsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

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
        agentId: String?,
        archiveStatus: String?,
        summarySearch: String?,
    ): Flow<PagingData<Conversation>> = sessionManager.currentGraph
        .flatMapLatest { it.allConversationsRepository.getConversationsPaged(agentId, archiveStatus, summarySearch) }

    override suspend fun loadNextPage() = sessionManager.withCurrentSession { it.allConversationsRepository.loadNextPage() }
    override suspend fun refresh() = sessionManager.withCurrentSession { it.allConversationsRepository.refresh() }
    override suspend fun clearForBackendSwitch() {
        _conversations.value = emptyList()
        _hasMore.value = true
        sessionManager.current.allConversationsRepository.clearForBackendSwitch()
    }

    override fun hasFreshConversations(maxAgeMs: Long): Boolean = current.hasFreshConversations(maxAgeMs)
    override suspend fun refreshIfStale(maxAgeMs: Long): Boolean = sessionManager.withCurrentSession { it.allConversationsRepository.refreshIfStale(maxAgeMs) }
    override fun handleOptimisticUpdate(conversation: Conversation) = current.handleOptimisticUpdate(conversation)
    override fun handleOptimisticDelete(conversationId: String) = current.handleOptimisticDelete(conversationId)
    override fun loadedCountEstimate(): ConversationCountEstimate? = current.loadedCountEstimate()

    @Deprecated("Use loadedCountEstimate() and render approximate/unknown states explicitly.")
    @Suppress("DEPRECATION")
    override suspend fun countConversations(): Int = sessionManager.withCurrentSession { it.allConversationsRepository.countConversations() }

    fun close() { proxyScope.cancel() }
}
