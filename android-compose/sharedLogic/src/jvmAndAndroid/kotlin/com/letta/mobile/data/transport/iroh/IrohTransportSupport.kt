package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.launch
import com.letta.mobile.data.transport.api.CronPauseCommand
import com.letta.mobile.data.transport.api.CronResumeCommand
import java.time.Instant
import java.util.UUID

/** Stateless parsing, identity, and diagnostics support for the Iroh transport. */
internal object IrohTransportSupport {
    fun frameFlowContent(frame: ServerFrame): Triple<String, String, String>? = when (frame) {
        is ServerFrame.AssistantMessage -> Triple(frame.otid ?: frame.id, "assistant_message", frame.content)
        is ServerFrame.ReasoningMessage -> Triple(frame.id, "reasoning_message", frame.reasoning)
        else -> null
    }

    fun conversationIdFromMessageListPath(path: String): String? {
        val marker = "/v1/conversations/"
        val start = path.indexOf(marker)
        if (start < 0) return null
        return path.substring(start + marker.length).substringBefore('/').substringBefore('?').takeIf { it.isNotBlank() }
    }

    fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    fun observerTurnCommand(agentId: String, conversationId: String): TurnCommand = TurnCommand(
        backendId = BackendId("iroh-app-server"),
        runtimeId = RuntimeId("iroh-observer"),
        agentId = AgentId(agentId),
        conversationId = ConversationId(conversationId),
        input = TurnInput.UserMessage(localMessageId = "iroh-observer-$conversationId", text = ""),
    )

    fun frameMessageId(frame: ServerFrame): String? = when (frame) {
        is ServerFrame.AssistantMessage -> frame.id
        is ServerFrame.ReasoningMessage -> frame.id
        is ServerFrame.ToolCallMessage -> frame.id
        is ServerFrame.ToolReturnMessage -> frame.id
        is ServerFrame.UserMessage -> frame.id
        else -> null
    }

    fun frameConversationId(frame: ServerFrame): String? = when (frame) {
        is ServerFrame.AssistantMessage -> frame.conversationId
        is ServerFrame.ReasoningMessage -> frame.conversationId
        is ServerFrame.ToolCallMessage -> frame.conversationId
        is ServerFrame.ToolReturnMessage -> frame.conversationId
        is ServerFrame.UserMessage -> frame.conversationId
        else -> null
    }

    fun frameId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
    fun nowIso(): String = Instant.now().toString()

    val READ_ONLY_ADMIN_RPC_METHODS = setOf(
        "message.list",
        "message.get",
        "tool_return.get",
        "conversation.list",
        "goal.get",
        "health.check",
        "agent.get",
        "agent.list",
        "agent.count",
        "agent.context",
        "subagent.list",
        "subagent.todos",
        "schedule.get",
        "schedule.list",
        "skill.list",
        "skill.list_agent",
        "slash_command.list",
        "slash_command.list_agent",
        "tool.get",
        "tool.list",
        "block.get",
        "block.list",
        "block.list_agent",
        "project.beadsRemoteStatus",
        "project.get",
        "project.list",
    )

    fun otherActiveConversationsLabel(registry: IrohTurnRegistry, conversationId: String): String =
        registry.concurrentTurns(excludingConversationId = IrohConversationId(conversationId))
            .joinToString(",") { it.conversationId }

    fun createNotebookHandlerFactory(
        peers: Set<String>?,
        store: com.letta.mobile.data.canvas.NotebookLocalStore?,
        directory: java.nio.file.Path?,
    ): ((computer.iroh.Endpoint, kotlinx.coroutines.CoroutineScope) -> NotebookEndpointSession)? =
        peers?.takeIf { it.isNotEmpty() }?.let { nonNullPeers ->
            when {
                store != null -> { endpoint, notebookScope ->
                    NotebookEndpointSession(store, endpoint, nonNullPeers, notebookScope)
                }
                directory != null -> { endpoint, notebookScope ->
                    NotebookEndpointSession(directory, endpoint, nonNullPeers, notebookScope)
                }
                else -> null
            }
        }

    fun subagentListFailure(failure: ScopedRpcFailure) = ServerFrame.SubagentListResponse(
        id = frameId("subagent_list"), ts = nowIso(), requestId = failure.requestId, success = false, error = failure.error,
    )

    fun subagentTodosFailure(failure: ScopedRpcFailure) = ServerFrame.SubagentTodosResponse(
        id = frameId("subagent_todos"), ts = nowIso(), requestId = failure.requestId, success = false, error = failure.error,
    )

    fun cronListFailure(failure: ScopedRpcFailure) = ServerFrame.CronListResponse(
        id = frameId("cron_list"), ts = nowIso(), requestId = failure.requestId, success = false, error = failure.error,
    )

    fun cronAddFailure(failure: ScopedRpcFailure) = ServerFrame.CronAddResponse(
        id = frameId("cron_add"), ts = nowIso(), requestId = failure.requestId, success = false, error = failure.error,
    )

    fun cronGetFailure(failure: ScopedRpcFailure) = ServerFrame.CronGetResponse(
        id = frameId("cron_get"), ts = nowIso(), requestId = failure.requestId, success = false, error = failure.error,
    )

    fun cronDeleteFailure(failure: ScopedRpcFailure) = ServerFrame.CronDeleteResponse(
        id = frameId("cron_delete"), ts = nowIso(), requestId = failure.requestId, success = false, error = failure.error,
    )

    fun cronDeleteAllFailure(failure: ScopedRpcFailure) = ServerFrame.CronDeleteAllResponse(
        id = frameId("cron_delete_all"), ts = nowIso(), requestId = failure.requestId, success = false, error = failure.error,
    )

    fun cronPauseFailure(failure: ScopedRpcFailure) = ServerFrame.CronPauseResponse(
        id = frameId("cron_pause"), ts = nowIso(), requestId = failure.requestId, success = false, error = failure.error,
    )

    fun cronResumeFailure(failure: ScopedRpcFailure) = ServerFrame.CronResumeResponse(
        id = frameId("cron_resume"), ts = nowIso(), requestId = failure.requestId, success = false, error = failure.error,
    )

    suspend fun executeCronPause(
        transport: IrohChannelTransport,
        command: CronPauseCommand,
    ): ServerFrame.CronPauseResponse =
        transport.cronTaskAction(
            op = "cron.pause",
            frameType = "cron_pause",
            timeoutMs = command.timeoutMs,
            body = buildJsonObject { put("task_id", command.taskId) },
            createSuccess = { id, ts, reqId -> ServerFrame.CronPauseResponse(id = id, ts = ts, requestId = reqId, success = true) },
            onFailure = ::cronPauseFailure,
        )

    suspend fun executeCronResume(
        transport: IrohChannelTransport,
        command: CronResumeCommand,
    ): ServerFrame.CronResumeResponse =
        transport.cronTaskAction(
            op = "cron.resume",
            frameType = "cron_resume",
            timeoutMs = command.timeoutMs,
            body = buildJsonObject {
                put("task_id", command.taskId)
                command.scheduledFor?.let { put("scheduled_for", it) }
            },
            createSuccess = { id, ts, reqId -> ServerFrame.CronResumeResponse(id = id, ts = ts, requestId = reqId, success = true) },
            onFailure = ::cronResumeFailure,
        )

    fun launchNotebook(scope: kotlinx.coroutines.CoroutineScope, starter: suspend () -> Unit) {
        scope.launch {
            try {
                starter()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                com.letta.mobile.util.Telemetry.event("IrohTransport", "notebook.start.failed", "error" to (error.message ?: error.toString()))
            }
        }
    }
}

@kotlinx.serialization.Serializable
internal data class SubagentListRpcResult(val subagents: List<com.letta.mobile.data.model.SubagentEntry> = emptyList())

@kotlinx.serialization.Serializable
internal data class SubagentTodosRpcResult(
    val found: Boolean = false,
    val subagent: com.letta.mobile.data.model.SubagentEntry? = null,
    val todos: List<com.letta.mobile.data.model.SubagentTodo> = emptyList(),
    @kotlinx.serialization.SerialName("todos_found") val todosFound: Boolean = false,
)

internal data class ScopedRpcFailure(val requestId: String, val error: String)


@kotlinx.serialization.Serializable
internal data class CronListRpcResult(val tasks: List<com.letta.mobile.data.model.CronTask> = emptyList())

@kotlinx.serialization.Serializable
internal data class CronMutationRpcResult(
    val found: Boolean = false,
    val task: com.letta.mobile.data.model.CronTask? = null,
    val warning: String? = null,
)

@kotlinx.serialization.Serializable
internal data class CronDeleteAllRpcResult(val deleted: Long = 0L)

