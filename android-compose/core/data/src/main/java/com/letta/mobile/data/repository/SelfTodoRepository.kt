package com.letta.mobile.data.repository

import android.util.Log
import com.letta.mobile.data.model.SubagentTodo
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

    // conversationId -> latest TodoWrite snapshot for that conversation.
    private val byConversation = MutableStateFlow<Map<String, List<SubagentTodo>>>(emptyMap())

    init {
        scope.launch { observeToolCalls() }
    }

    override fun latestForFlow(conversationId: String): Flow<List<SubagentTodo>> =
        byConversation.map { it[conversationId].orEmpty() }

    override fun latestFor(conversationId: String): List<SubagentTodo> =
        byConversation.value[conversationId].orEmpty()

    /** Test/preview hook: directly stage a snapshot for a conversation. */
    internal fun stage(conversationId: String, todos: List<SubagentTodo>) {
        byConversation.value = byConversation.value.toMutableMap().apply {
            this[conversationId] = todos
        }
    }

    private suspend fun observeToolCalls() {
        transport.events.collect { frame ->
            if (frame !is ServerFrame.ToolCallMessage) return@collect
            val conversationId = frame.conversationId.takeIf { it.isNotBlank() } ?: return@collect
            val todoCall = frame.allToolCalls().firstOrNull { it.name == TODO_WRITE_TOOL } ?: return@collect
            val todos = parseTodos(todoCall.arguments) ?: return@collect
            byConversation.value = byConversation.value.toMutableMap().apply {
                this[conversationId] = todos
            }
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
