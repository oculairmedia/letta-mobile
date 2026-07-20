package com.letta.mobile.data.session

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.repository.api.SubagentParentScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedSubagentRepository @Inject constructor(
    private val sessionManager: SessionManager,
) : ISubagentRepository {
    override fun activeSubagentsFlow(scope: SubagentParentScope): Flow<List<SubagentEntry>> =
        sessionManager.currentGraph.flatMapLatest { it.subagentRepository.activeSubagentsFlow(scope) }

    override fun currentActiveSubagents(scope: SubagentParentScope): List<SubagentEntry> =
        sessionManager.currentGraph.value.subagentRepository.currentActiveSubagents(scope)

    override suspend fun refresh(): Result<List<SubagentEntry>> =
        sessionManager.withCurrentSession { it.subagentRepository.refresh() }

    override suspend fun todos(toolCallId: String): Result<List<SubagentTodo>> =
        sessionManager.withCurrentSession { it.subagentRepository.todos(toolCallId) }
}
