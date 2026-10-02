@file:OptIn(ExperimentalUuidApi::class)

package com.letta.mobile.data.transport.appserver

import com.letta.mobile.data.chat.send.OutboundMessageCreate
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ErrorMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.runtime.AppServerRuntimeEventMapper
import com.letta.mobile.data.runtime.TurnFailureNotices
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.TimelineTransport
import com.letta.mobile.data.timeline.TimelineTransportHttpException
import com.letta.mobile.data.transport.WsFrameMapper
import com.letta.mobile.data.transport.iroh.RuntimeEventServerFrameMapper
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnEngine
import com.letta.mobile.runtime.TurnInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import com.letta.mobile.runtime.ConversationId as RuntimeConversationId

/** An `admin_rpc` method name, e.g. `message.list`. */
@JvmInline
value class AppServerAdminMethod(val value: String)

/** One `admin_rpc` call answered with its `result`; throws when it failed. */
fun interface AppServerAdminCall {
    suspend fun call(method: AppServerAdminMethod, params: JsonObject): JsonElement?
}

/** What [AppServerTimelineTransport] reads from the live App Server connection. */
class AppServerTimelineConnection(
    /** Runs a turn; the App Server engine, so sends get its lease, queue and approval handling. */
    val turnEngine: TurnEngine,
    /** Every inbound frame, for the passive view of turns this client did not start. */
    val events: Flow<AppServerReceivedFrame>,
    val isConnected: Flow<Boolean>,
    val admin: AppServerAdminCall,
    /** The agent that owns a conversation; sends and the stream need it for the runtime scope. */
    val agentIdFor: suspend (ConversationId) -> AgentId,
    val backendId: BackendId = BackendId(APP_SERVER_TIMELINE_BACKEND_ID),
    val heartbeatInterval: Duration = APP_SERVER_STREAM_HEARTBEAT_MS.milliseconds,
)

/**
 * letta-mobile-o4ygk.4.5: a [TimelineTransport] over the App Server protocol alone (a turn engine
 * plus `admin_rpc` reads), in commonMain so any client that holds an App Server connection can
 * drive the shared [com.letta.mobile.data.timeline.TimelineSyncLoop] with it. The web client is the
 * first: it has no HTTP admin API, only the App Server socket (WebSocket or Iroh).
 *
 * Sends and the passive stream map runtime events to messages through the same mappers desktop's
 * App Server gateway uses ([RuntimeEventServerFrameMapper], [WsFrameMapper]), so the timeline rows
 * have the same ids and shapes on every client. While this client's own turn runs on a
 * conversation, the passive stream skips that conversation's frames: the send flow already carries
 * them, and folding both in would duplicate the reply.
 *
 * The [TimelineTransport] overrides only adapt the interface's raw ids; the work is done by the
 * typed members below them. Older-history paging (`listConversationMessagesBefore`) keeps the
 * interface default (no such route) until the web binds the canonical route (letta-mobile-o4ygk.4.7).
 */
class AppServerTimelineTransport(
    private val connection: AppServerTimelineConnection,
) : TimelineTransport {
    private val activeSends = MutableStateFlow<Set<ConversationId>>(emptySet())
    private val observerMapper = AppServerRuntimeEventMapper()

    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> = send(ConversationId(conversationId), request)

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> =
        stream(ConversationId(conversationId))

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = list(ConversationId(conversationId), AppServerMessagePage(limit = limit, after = after, order = order))

    /** The App Server lists by conversation; an agent-wide read is the conversation's when one is named. */
    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> =
        conversationId?.let { list(ConversationId(it), AppServerMessagePage(limit = limit, order = order)) }.orEmpty()

    /** Runs [request] as a turn on [conversation] and emits the turn's messages as they stream. */
    suspend fun send(conversation: ConversationId, request: MessageCreateRequest): Flow<LettaMessage> {
        val ids = sendIds(conversation, connection.agentIdFor(conversation))
        val command = turnCommand(ids, userInput(OutboundMessageCreate.decode(request)))
        return flow {
            markSending(conversation)
            try {
                val turn = AppServerSendTurn(ids)
                connection.turnEngine.runTurn(command).collect { draft ->
                    turn.messagesFor(draft).forEach { emit(it) }
                    turn.failureReason?.let { reason ->
                        throw TimelineTransportHttpException(502, "App Server turn failed: $reason")
                    }
                }
            } finally {
                markSettled(conversation)
            }
        }
    }

    /** [conversation]'s turns this client did not start, with heartbeats while the agent is idle. */
    suspend fun stream(conversation: ConversationId): Flow<TimelineStreamFrame> {
        val agent = runCatching { connection.agentIdFor(conversation) }.getOrDefault(AgentId(""))
        return merge(observedFrames(conversation, agent), heartbeats(connection.heartbeatInterval))
    }

    /** One `message.list` page of [conversation]. */
    suspend fun list(conversation: ConversationId, page: AppServerMessagePage): List<LettaMessage> {
        val result = try {
            connection.admin.call(MESSAGE_LIST, page.params(conversation))
        } catch (failure: IllegalStateException) {
            throw TimelineTransportHttpException(502, failure.message ?: "${MESSAGE_LIST.value} failed", failure)
        }
        return decodeAppServerMessageList(result)
    }

    private fun observedFrames(conversation: ConversationId, agent: AgentId): Flow<TimelineStreamFrame> = flow {
        connection.events.collect { received ->
            observedMessages(received, conversation, agent).forEach { emit(TimelineStreamFrame.Message(it)) }
        }
    }

    private fun heartbeats(interval: Duration): Flow<TimelineStreamFrame> = flow {
        while (true) {
            delay(interval)
            if (!connection.isConnected.first()) break
            emit(TimelineStreamFrame.Heartbeat)
        }
    }

    private fun observedMessages(
        received: AppServerReceivedFrame,
        conversation: ConversationId,
        fallbackAgent: AgentId,
    ): List<LettaMessage> {
        val delta = observableDelta(received, conversation) ?: return emptyList()
        val agent = delta.runtime.agentId.takeIf { it.isNotBlank() }?.let(::AgentId) ?: fallbackAgent
        val ids = observerIds(conversation, agent)
        val observer = turnCommand(ids, TurnInput.UserMessage(localMessageId = "app-server-observer-${conversation.value}", text = ""))
        return observerMapper.map(observer, received).flatMap { draft -> draft.toLettaMessages(ids) }
    }

    /** [received] as a stream delta of [conversation] that no send of this client is already carrying. */
    private fun observableDelta(
        received: AppServerReceivedFrame,
        conversation: ConversationId,
    ): AppServerInboundFrame.StreamDelta? {
        val delta = received.frame as? AppServerInboundFrame.StreamDelta ?: return null
        if (delta.runtime.conversationId != conversation.value) return null
        return delta.takeUnless { isSending(conversation) }
    }

    private fun markSending(conversation: ConversationId) = activeSends.update { it + conversation }

    private fun markSettled(conversation: ConversationId) = activeSends.update { it - conversation }

    private fun isSending(conversation: ConversationId): Boolean = conversation in activeSends.value

    private fun turnCommand(ids: AppServerTurnIds, input: TurnInput): TurnCommand = TurnCommand(
        backendId = connection.backendId,
        runtimeId = RuntimeId("${connection.backendId.value}:${ids.conversation.value}"),
        agentId = ids.agent,
        conversationId = RuntimeConversationId(ids.conversation.value),
        input = input,
    )
}

/** A send's ids: fresh turn and fallback run ids, so each send is its own run. */
private fun sendIds(conversation: ConversationId, agent: AgentId) = AppServerTurnIds(
    agent = agent,
    conversation = conversation,
    turnId = "app-server-turn-${Uuid.random()}",
    fallbackRunId = "app-server-run-${Uuid.random()}",
)

/** The passive stream's ids: stable per conversation, so its rows keep their keys across frames. */
private fun observerIds(conversation: ConversationId, agent: AgentId) = AppServerTurnIds(
    agent = agent,
    conversation = conversation,
    turnId = "app-server-stream-turn-${conversation.value}",
    fallbackRunId = "app-server-stream-run-${conversation.value}",
)

private fun userInput(outbound: OutboundMessageCreate): TurnInput = TurnInput.UserMessage(
    localMessageId = outbound.otid ?: "app-server-local-${Uuid.random()}",
    text = outbound.text,
    contentPartsJson = outbound.contentParts?.toString(),
)

/** The ids a turn's runtime events are mapped under. */
internal data class AppServerTurnIds(
    val agent: AgentId,
    val conversation: ConversationId,
    val turnId: String,
    /** Used when a draft carries no run id of its own. */
    val fallbackRunId: String,
)

/**
 * One send's runtime events as timeline messages. A failed terminal becomes an error row (unless
 * the reply already landed) and sets [failureReason], which fails the send so the loop marks the
 * prompt failed.
 */
internal class AppServerSendTurn(private val ids: AppServerTurnIds) {
    private var deliveredAssistantContent = false
    private var mainReplyCompleted = false

    /** Set once the turn failed with a notice worth showing; the caller fails the send on it. */
    var failureReason: String? = null
        private set

    fun messagesFor(draft: RuntimeEventDraft): List<LettaMessage> {
        val lifecycle = draft.payload as? RuntimeEventPayload.RunLifecycleChanged
        if (lifecycle?.status == RuntimeRunStatus.Failed) return failure(draft, lifecycle)
        if (completesMainReply(draft.payload)) mainReplyCompleted = true
        return draft.toLettaMessages(ids).onEach { message ->
            if (message is AssistantMessage && message.content.isNotBlank()) deliveredAssistantContent = true
        }
    }

    private fun failure(draft: RuntimeEventDraft, lifecycle: RuntimeEventPayload.RunLifecycleChanged): List<LettaMessage> {
        val notice = TurnFailureNotices.forFailedTerminal(
            reason = lifecycle.reason,
            deliveredAssistantContent = deliveredAssistantContent,
            mainReplyCompleted = mainReplyCompleted,
        ) ?: return emptyList()
        failureReason = lifecycle.reason ?: "unknown"
        val runId = draft.runId?.value ?: ids.fallbackRunId
        return listOf(
            ErrorMessage(
                id = "turn-failed-$runId",
                contentRaw = JsonPrimitive(notice.message),
                code = notice.kind,
                runId = runId,
            ),
        )
    }

    private fun completesMainReply(payload: RuntimeEventPayload): Boolean = when (payload) {
        is RuntimeEventPayload.RemoteStreamFrame -> payload.messageType == "stop_reason" &&
            TurnFailureNotices.isCompletedMainReplyStopReason(TurnFailureNotices.stopReasonFromStreamDeltaBody(payload.body))
        is RuntimeEventPayload.RunLifecycleChanged -> payload.status == RuntimeRunStatus.Completed
        else -> false
    }
}

private fun RuntimeEventDraft.toLettaMessages(ids: AppServerTurnIds): List<LettaMessage> =
    RuntimeEventServerFrameMapper.map(
        payload = payload,
        context = RuntimeEventServerFrameMapper.Context(
            agentId = agentId?.value?.takeIf { it.isNotBlank() } ?: ids.agent.value,
            conversationId = conversationId?.value?.takeIf { it.isNotBlank() } ?: ids.conversation.value,
            turnId = ids.turnId,
            runId = runId?.value?.takeIf { it.isNotBlank() } ?: ids.fallbackRunId,
        ),
    ).mapNotNull(WsFrameMapper::toLettaMessage)

/** The cursors one `message.list` accepts. */
data class AppServerMessagePage(
    val limit: Int? = null,
    val after: String? = null,
    val before: String? = null,
    val order: String? = null,
) {
    fun params(conversation: ConversationId): JsonObject = buildJsonObject {
        put("conversation_id", conversation.value)
        limit?.let { put("limit", it.toString()) }
        after?.let { put("after", it) }
        before?.let { put("before", it) }
        order?.let { put("order", it) }
    }
}

/** `message.list` answers with a bare array or `{ "messages": [...] }`; both decode to the list. */
internal fun decodeAppServerMessageList(result: JsonElement?): List<LettaMessage> {
    val messages = (result as? JsonObject)?.get("messages") ?: result
    val array = messages as? JsonArray ?: return emptyList()
    return AppServerProtocol.json.decodeFromJsonElement(ListSerializer(LettaMessage.serializer()), array)
}

private val MESSAGE_LIST = AppServerAdminMethod("message.list")

const val APP_SERVER_TIMELINE_BACKEND_ID: String = "app-server"

/** Idle-stream heartbeat, matching the Iroh admin gateway's, so the loop's silence timeout never fires on a quiet agent. */
const val APP_SERVER_STREAM_HEARTBEAT_MS: Long = 15_000L
