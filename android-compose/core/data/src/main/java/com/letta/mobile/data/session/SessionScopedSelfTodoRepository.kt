package com.letta.mobile.data.session

import com.letta.mobile.data.model.SelfTodoSnapshot
import com.letta.mobile.data.repository.api.ISelfTodoRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * letta-mobile-gnyf7: session-scoped facade over the per-session
 * [com.letta.mobile.data.repository.SelfTodoRepository], mirroring
 * [SessionScopedSubagentRepository]. Re-targets to the current session's
 * repository whenever the backend session rebuilds so a switched
 * connection's TodoWrite stream is observed.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedSelfTodoRepository internal constructor(
    private val repositories: Flow<ISelfTodoRepository>,
    private val currentRepository: () -> ISelfTodoRepository,
) : ISelfTodoRepository {
    @Inject
    constructor(sessionManager: SessionManager) : this(
        repositories = sessionManager.currentGraph.map { it.selfTodoRepository },
        currentRepository = { sessionManager.currentGraph.value.selfTodoRepository },
    )

    override fun snapshotForFlow(conversationId: String): Flow<SelfTodoSnapshot> =
        repositories.flatMapLatest { it.snapshotForFlow(conversationId) }

    override fun snapshotFor(conversationId: String): SelfTodoSnapshot =
        currentRepository().snapshotFor(conversationId)
}
