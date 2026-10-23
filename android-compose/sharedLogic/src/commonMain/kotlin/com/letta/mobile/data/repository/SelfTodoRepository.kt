package com.letta.mobile.data.repository

import com.letta.mobile.data.model.SelfTodoSnapshot
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.model.parseAgentTodos
import com.letta.mobile.data.repository.api.ISelfTodoRepository
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ToolCallPayload
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Tracks the MAIN/foreground agent's own TodoWrite plan
 * per conversation by observing the conversation stream
 * ([IChannelTransport.events]) for `tool_call_message` frames whose tool is
 * `TodoWrite`. The latest snapshot per `conversationId` is folded into a
 * single [MutableStateFlow] map (snapshot-by-replacement).
 *
 * Platform-neutral (commonMain) so Android and Desktop share one impl
 * (Phase 4c). The caller supplies the session lifecycle scope for its observer.
 */
open class SelfTodoRepository(
    private val transport: IChannelTransport,
    scope: CoroutineScope,
) : ISelfTodoRepository {
    private val snapshots = MutableStateFlow<Map<String, SelfTodoSnapshot>>(emptyMap())
    private val activeRuns = mutableMapOf<String, ActiveRun>()

    init {
        scope.launch { observeFrames() }
    }

    override fun snapshotForFlow(conversationId: String): Flow<SelfTodoSnapshot> =
        snapshots.map { it[conversationId] ?: SelfTodoSnapshot() }

    override fun snapshotFor(conversationId: String): SelfTodoSnapshot =
        snapshots.value[conversationId] ?: SelfTodoSnapshot()

    /** Test/preview hook: directly stage a snapshot for a conversation. */
    internal fun stage(conversationId: String, todos: List<SubagentTodo>) {
        updateSnapshot(conversationId) { it.copy(todos = todos) }
    }

    private suspend fun observeFrames() {
        transport.events.collect { frame ->
            when (frame) {
                is ServerFrame.TurnStarted -> recordStarted(frame)
                is ServerFrame.TurnDone -> recordDone(frame)
                is ServerFrame.ToolCallMessage -> {
                    val conversationId = frame.conversationId.takeIf { it.isNotBlank() } ?: return@collect
                    val todos = frame.allToolCalls().mapNotNull { parseAgentTodos(it.name, it.arguments) }
                        .lastOrNull() ?: return@collect
                    updateSnapshot(conversationId) { it.copy(todos = todos) }
                }
                else -> Unit
            }
        }
    }

    private fun recordStarted(frame: ServerFrame.TurnStarted) {
        activeRuns.entries.removeAll { it.value.conversationId == frame.conversationId }
        activeRuns[frame.runId] = ActiveRun(frame.conversationId, frame.turnId)
        updateSnapshot(frame.conversationId) { it.copy(lifecycleStatus = SubagentStatus.RUNNING) }
    }

    private fun recordDone(frame: ServerFrame.TurnDone) {
        val activeRun = activeRuns[frame.runId]?.takeIf { it.turnId == frame.turnId } ?: return
        activeRuns.remove(frame.runId)
        val status = when (frame.status.trim().lowercase()) {
            "completed", "complete", "success", "succeeded" -> SubagentStatus.COMPLETED
            "cancelled", "canceled" -> SubagentStatus.CANCELLED
            "failed", "error" -> SubagentStatus.FAILED
            else -> return
        }
        updateSnapshot(activeRun.conversationId) { it.copy(lifecycleStatus = status) }
    }

    private fun updateSnapshot(
        conversationId: String,
        transform: (SelfTodoSnapshot) -> SelfTodoSnapshot,
    ) {
        snapshots.value = snapshots.value.toMutableMap().apply {
            this[conversationId] = transform(get(conversationId) ?: SelfTodoSnapshot())
        }
    }

    private data class ActiveRun(
        val conversationId: String,
        val turnId: String,
    )
}

/**
 * The shim emits the tool call under both `tool_call` (singular) and
 * `tool_calls` (array); flatten to the union so we don't miss either shape.
 */
private fun ServerFrame.ToolCallMessage.allToolCalls(): List<ToolCallPayload> =
    buildList {
        toolCall?.let { add(it) }
        toolCalls?.let { addAll(it) }
    }
