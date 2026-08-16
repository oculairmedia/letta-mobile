package com.letta.mobile.data.repository

import android.util.Log
import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.model.SelfTodoSnapshot
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.repository.api.ISelfTodoRepository
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ToolCallPayload
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Long-lived coroutine scope [SelfTodoRepository] uses for its event
 * observer. Defaults to [Dispatchers.Default] + a fresh [SupervisorJob] —
 * same pattern [SubagentRepository] uses. Exposed as a factory so tests can
 * substitute a [kotlinx.coroutines.test.TestScope].
 */
internal fun defaultSelfTodoScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * letta-mobile-gnyf7: tracks the MAIN/foreground agent's own TodoWrite plan
 * per conversation by observing the conversation stream
 * ([IChannelTransport.events]) for `tool_call_message` frames whose tool is
 * `TodoWrite`. The latest snapshot per `conversationId` is folded into a
 * single [MutableStateFlow] map (snapshot-by-replacement).
 *
 * This is deliberately client-side and reuses the existing mobile WS:
 * TodoWrite for the primary agent is NOT carried by the shim's dispatched-
 * subagent registry (MOBILE_WS_PROTOCOL.md §13) — it shows up as an ordinary
 * tool call in the live stream. We parse the tool-call `arguments` (the
 * canonical TodoWrite shape `{"todos":[{content,status,activeForm}, ...]}`)
 * and key the result by the frame's `conversation_id`.
 *
 * Perf: each emission for a conversation is the full todo list, never a
 * delta, so downstream reduces by simple replacement — no per-frame
 * rebuilds, preserving the rmzmo streaming-jank work.
 */
@Singleton
open class SelfTodoRepository(
    private val transport: IChannelTransport,
    scope: CoroutineScope,
) : ISelfTodoRepository {
    /**
     * Hilt-friendly constructor — uses a fresh [defaultSelfTodoScope] tied
     * to the singleton's lifetime. Tests inject their own scope via the
     * primary constructor.
     */
    @Inject
    constructor(transport: IChannelTransport) : this(transport, defaultSelfTodoScope())

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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
                    val todoCall = frame.allToolCalls().firstOrNull { it.name == TODO_WRITE_TOOL } ?: return@collect
                    val todos = parseTodos(todoCall.arguments) ?: return@collect
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

    /**
     * Parse the TodoWrite tool-call `arguments` JSON into the canonical
     * [SubagentTodo] list. Returns null (caller ignores the frame) when the
     * payload can't be parsed or carries no `todos` array, so a malformed
     * delta never clobbers a previously good snapshot.
     */
    private fun parseTodos(arguments: String): List<SubagentTodo>? {
        if (arguments.isBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(arguments).jsonObject
            val todosArray = root["todos"] as? JsonArray ?: return null
            todosArray.map { element ->
                val obj = element.jsonObject
                SubagentTodo(
                    content = obj["content"]?.jsonPrimitive?.content.orEmpty(),
                    status = obj["status"]?.jsonPrimitive?.content.orEmpty(),
                    activeForm = obj["activeForm"]?.jsonPrimitive?.content.orEmpty(),
                )
            }
        }.onFailure { e ->
            Log.w(TAG, "failed to parse TodoWrite arguments: ${e.message}")
        }.getOrNull()
    }

    private companion object {
        const val TAG = "SelfTodoRepository"
        const val TODO_WRITE_TOOL = "TodoWrite"
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
