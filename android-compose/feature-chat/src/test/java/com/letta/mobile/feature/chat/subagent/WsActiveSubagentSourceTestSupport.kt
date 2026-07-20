package com.letta.mobile.feature.chat.subagent

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.repository.api.SubagentParentScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

@JvmInline internal value class ToolCallRef(val value: String)
@JvmInline internal value class TaskRef(val value: String)
@JvmInline internal value class AgentRef(val value: String)
@JvmInline internal value class ConversationRef(val value: String)

internal data class EntryRefs(
    val toolCallId: ToolCallRef = ToolCallRef(""),
    val taskId: TaskRef? = null,
    val subagentAgentId: AgentRef? = null,
    val subagentConversationId: ConversationRef? = null,
    val parentAgentId: AgentRef = AgentRef("agent-parent"),
    val parentConversationId: ConversationRef = ConversationRef("default"),
)

internal class FakeSubagentRepository(
    initial: List<SubagentEntry> = emptyList(),
) : ISubagentRepository {
    val state = MutableStateFlow(initial)
    var refreshResult: Result<List<SubagentEntry>>? = null
    var refreshCalls = 0
    override fun activeSubagentsFlow(scope: SubagentParentScope): Flow<List<SubagentEntry>> =
        state.map { entries -> entries.filter { it.inScope(scope) } }
    override fun currentActiveSubagents(scope: SubagentParentScope): List<SubagentEntry> =
        state.value.filter { it.inScope(scope) }
    private fun SubagentEntry.inScope(scope: SubagentParentScope): Boolean =
        parentAgentId == scope.parentAgentId && parentConversationId == scope.parentConversationId
    override suspend fun refresh(): Result<List<SubagentEntry>> {
        refreshCalls += 1
        return refreshResult ?: Result.success(state.value)
    }
    override suspend fun todos(toolCallId: String): Result<List<SubagentTodo>> = Result.success(emptyList())
}

internal fun entry(
    refs: EntryRefs,
    status: String = SubagentStatus.RUNNING,
    terminalAtEpochMs: Long? = null,
) = SubagentEntry(
    toolCallId = refs.toolCallId.value,
    description = "desc ${refs.toolCallId.value}",
    subagentType = "general-purpose",
    status = status,
    taskId = refs.taskId?.value,
    subagentAgentId = refs.subagentAgentId?.value,
    subagentConversationId = refs.subagentConversationId?.value,
    parentAgentId = refs.parentAgentId.value,
    parentConversationId = refs.parentConversationId.value,
    terminalAtEpochMs = terminalAtEpochMs,
)

internal fun wsActiveSubagentSource(
    repo: ISubagentRepository,
    scope: CoroutineScope,
    conversationId: MutableStateFlow<String?> = MutableStateFlow("default"),
) = WsActiveSubagentSource(repo, scope, "agent-parent", conversationId)
