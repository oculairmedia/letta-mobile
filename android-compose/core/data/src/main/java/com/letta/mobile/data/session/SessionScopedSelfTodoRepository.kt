package com.letta.mobile.data.session

import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.repository.api.ISelfTodoRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * letta-mobile-gnyf7: session-scoped facade over the per-session
 * [com.letta.mobile.data.repository.SelfTodoRepository], mirroring
 * [SessionScopedSubagentRepository]. Re-targets to the current session's
 * repository whenever the backend session rebuilds so a switched
 * connection's TodoWrite stream is observed.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedSelfTodoRepository @Inject constructor(
    private val sessionManager: SessionManager,
) : ISelfTodoRepository {
    override fun latestForFlow(conversationId: String): Flow<List<SubagentTodo>> =
        sessionManager.currentGraph.flatMapLatest {
            it.selfTodoRepository.latestForFlow(conversationId)
        }

    override fun latestFor(conversationId: String): List<SubagentTodo> =
        sessionManager.currentGraph.value.selfTodoRepository.latestFor(conversationId)
}
