package com.letta.mobile.data.swarm

import com.letta.mobile.data.runtime.onStreamChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** How the fake host answers a user message. */
internal enum class HostAck { Started, Queued, Rejected }

/** What the fake host's agent does after accepting a message. */
internal enum class HostTurn {
    /** Replies, then `turn_finished`. */
    Reply,

    /** Asks to run a tool (`can_use_tool`) and waits. */
    NeedsApproval,

    /** The link drops before any terminal; the reply lands in the history meanwhile. */
    DropLinkThenReply,

    /** Nothing happens after the ack. */
    Silent,
}

/**
 * letta-mobile-jvctq: a wire-shaped fake App Server host for the swarm messenger: a scoped agent
 * list, conversations owned by agents, a message history served by `message.list`, and a turn
 * script driven through the real shared engine (runtime_start, input + input_accepted, stream
 * frames, turn_finished, control_request).
 */
internal class SwarmFakeHost(
    private val scope: CoroutineScope,
    var ack: HostAck = HostAck.Started,
    var turn: HostTurn = HostTurn.Reply,
    /** Whether the stored user message carries the client message id as its `otid`. */
    var echoOtid: Boolean = true,
) : AppServerClient {
    override val events = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
    override val isConnected = MutableStateFlow(true)

    val agents = mutableListOf(PM_AGENT to "PM-letta-mobile", OTHER_AGENT to "Other")
    val conversations = mutableMapOf(PM_CONVERSATION to PM_AGENT, OTHER_CONVERSATION to OTHER_AGENT)
    val history = mutableMapOf<String, MutableList<JsonObject>>()
    val inputs = mutableListOf<AppServerCommand.Input>()
    val aborts = mutableListOf<AppServerCommand.AbortMessage>()
    val adminCalls = mutableListOf<String>()
    private var nextId = 0

    val userMessages: List<AppServerInputPayload.CreateMessage>
        get() = inputs.mapNotNull { it.payload as? AppServerInputPayload.CreateMessage }

    val approvalResponses: List<AppServerInputPayload.ApprovalResponse>
        get() = inputs.mapNotNull { it.payload as? AppServerInputPayload.ApprovalResponse }

    fun store(conversation: String, message: JsonObject) {
        history.getOrPut(conversation) { mutableListOf() } += message
    }

    fun userMessage(text: String, otid: String?, sender: String? = null): JsonObject = buildJsonObject {
        put("id", "message-${++nextId}")
        put("message_type", "user_message")
        put("content", text)
        otid?.let { put("otid", it) }
        sender?.let { put("sender_id", it) }
    }

    fun assistantMessage(text: String): JsonObject = buildJsonObject {
        put("id", "message-${++nextId}")
        put("message_type", "assistant_message")
        put("content", text)
    }

    override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = AppServerInboundFrame.RuntimeStartResponse(
        requestId = command.requestId,
        success = true,
        runtime = AppServerRuntimeScope(requireNotNull(command.agentId), requireNotNull(command.conversationId)),
    )

    override suspend fun input(command: AppServerCommand.Input) {
        inputs += command
    }

    override suspend fun inputAwaitingAcceptance(command: AppServerCommand.Input): AppServerInboundFrame.InputAccepted {
        inputs += command
        val message = (command.payload as? AppServerInputPayload.CreateMessage)?.messages?.singleOrNull()
        if (message != null && ack != HostAck.Rejected) {
            val text = (message.content as JsonPrimitive).content
            store(command.runtime.conversationId, userMessage(text, message.clientMessageId.takeIf { echoOtid }))
            scope.launch { play(command.runtime) }
        }
        return AppServerInboundFrame.InputAccepted(
            requestId = command.requestId.orEmpty(),
            runtime = command.runtime,
            accepted = ack != HostAck.Rejected,
            disposition = if (ack == HostAck.Queued) "queued" else "started",
            error = if (ack == HostAck.Rejected) "agent is archived" else null,
        )
    }

    private suspend fun play(runtime: AppServerRuntimeScope) {
        delay(FRAME_DELAY_MS)
        when (turn) {
            HostTurn.Reply -> {
                store(runtime.conversationId, assistantMessage(REPLY))
                emit(assistantDelta(runtime))
                emit(turnFinished(runtime))
            }
            HostTurn.NeedsApproval -> emit(approvalRequest(runtime))
            HostTurn.DropLinkThenReply -> {
                isConnected.value = false
                store(runtime.conversationId, assistantMessage(REPLY))
            }
            HostTurn.Silent -> Unit
        }
    }

    private fun emit(frame: AppServerInboundFrame) {
        events.tryEmit(frame.onStreamChannel())
    }

    override suspend fun sync(command: AppServerCommand.Sync) =
        AppServerInboundFrame.SyncResponse(requestId = command.requestId.orEmpty(), runtime = command.runtime, success = true)

    override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse {
        aborts += command
        return AppServerInboundFrame.AbortMessageResponse(
            requestId = command.requestId.orEmpty(),
            runtime = command.runtime,
            aborted = true,
            success = true,
        )
    }

    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit

    override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse {
        adminCalls += command.method
        val params = command.params ?: JsonObject(emptyMap())
        val result: Result<JsonElement> = when (command.method) {
            "agent.list" -> Result.success(agentPage(params))
            "conversation.get" -> conversation(params.string("conversation_id"))
            "conversation.create" -> Result.success(createConversation(params.string("agent_id").orEmpty()))
            "message.list" -> Result.success(messagePage(params))
            else -> Result.failure(IllegalArgumentException("unexpected ${command.method}"))
        }
        return result.fold(
            onSuccess = { AppServerInboundFrame.AdminRpcResponse(requestId = command.requestId, success = true, result = it) },
            onFailure = { AppServerInboundFrame.AdminRpcResponse(requestId = command.requestId, success = false, error = it.message) },
        )
    }

    private fun agentPage(params: JsonObject): JsonArray {
        if (params.string("offset") != "0") return JsonArray(emptyList())
        return JsonArray(agents.map { (id, name) -> buildJsonObject { put("id", id); put("name", name) } })
    }

    private fun conversation(id: String?): Result<JsonElement> {
        val owner = conversations[id] ?: return Result.failure(IllegalStateException("conversation not found"))
        return Result.success(buildJsonObject { put("id", id); put("agent_id", owner) })
    }

    private fun createConversation(agent: String): JsonElement {
        val id = "conversation-new-${conversations.size}"
        conversations[id] = agent
        return buildJsonObject { put("id", id); put("agent_id", agent) }
    }

    private fun messagePage(params: JsonObject): JsonArray {
        val all = history[params.string("conversation_id")].orEmpty()
        val limit = params.string("limit")?.toInt() ?: all.size
        val after = params.string("after")
        val page = if (after != null) {
            all.dropWhile { it.string("id") != after }.drop(1).take(limit)
        } else {
            all.asReversed().take(limit)
        }
        return JsonArray(page)
    }

    companion object {
        const val PM_AGENT = "agent-pm"
        const val OTHER_AGENT = "agent-other"
        const val PM_CONVERSATION = "conversation-pm"
        const val OTHER_CONVERSATION = "conversation-other"
        const val REPLY = "On it. Merging the PR after CI."
        const val FRAME_DELAY_MS = 10L

        private fun assistantDelta(runtime: AppServerRuntimeScope) = AppServerInboundFrame.StreamDelta(
            runtime = runtime,
            eventSeq = 1,
            emittedAt = EMITTED_AT,
            idempotencyKey = "delta-1",
            delta = buildJsonObject {
                put("message_type", "assistant_message")
                put("run_id", RUN_ID)
                put("content", REPLY)
            },
        )

        private fun turnFinished(runtime: AppServerRuntimeScope) = AppServerInboundFrame.TurnFinished(
            runtime = runtime,
            eventSeq = 2,
            emittedAt = EMITTED_AT,
            idempotencyKey = "turn_finished:1",
            turnId = "turn-1",
            stopReason = "end_turn",
            runId = RUN_ID,
        )

        private fun approvalRequest(runtime: AppServerRuntimeScope) = AppServerInboundFrame.ControlRequest(
            requestId = APPROVAL_ID,
            request = buildJsonObject {
                put("subtype", "can_use_tool")
                put("tool_name", "Bash")
                put("tool_call_id", "call-1")
                put("input", buildJsonObject { put("command", "git push") })
            },
            agentId = runtime.agentId,
            conversationId = runtime.conversationId,
        )

        const val APPROVAL_ID = "approval-1"
        private const val RUN_ID = "run-1"
        private const val EMITTED_AT = "2026-10-16T00:00:00Z"
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** An in-memory outbox for the messenger tests. */
internal class InMemorySwarmOutbox : SwarmOutbox {
    val entries = linkedMapOf<ClientMessageId, OutboxEntry>()

    override suspend fun get(id: ClientMessageId): OutboxEntry? = entries[id]

    override suspend fun put(entry: OutboxEntry) {
        entries[entry.id] = entry
    }

    override suspend fun remove(id: ClientMessageId) {
        entries.remove(id)
    }

    override suspend fun list(): List<OutboxEntry> = entries.values.toList()
}

/** Dials follow [steps] in order: a host (served through the real [AppServerSwarmSession]) or a link failure. */
internal class ScriptedSwarmLink(private val scope: CoroutineScope, vararg steps: Step) : SwarmLink {
    sealed interface Step {
        data class Host(val host: SwarmFakeHost) : Step

        data class Fail(val failure: LinkFailure) : Step
    }

    private val remaining = ArrayDeque(steps.toList())
    var dials = 0
        private set

    override suspend fun <T> withSession(block: suspend (SwarmSession) -> T): LinkResult<T> {
        dials += 1
        return when (val step = remaining.removeFirstOrNull() ?: error("no more dials scripted")) {
            is Step.Fail -> LinkResult.Failed(step.failure)
            is Step.Host -> {
                step.host.isConnected.value = true
                val session = AppServerSwarmSession(step.host, scope)
                try {
                    LinkResult.Ok(block(session))
                } finally {
                    session.close()
                }
            }
        }
    }
}
