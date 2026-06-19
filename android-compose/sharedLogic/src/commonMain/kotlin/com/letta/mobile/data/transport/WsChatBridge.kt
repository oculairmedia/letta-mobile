package com.letta.mobile.data.transport

import com.letta.mobile.data.a2ui.A2uiFrameEvent
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.buildContentParts
import com.letta.mobile.data.model.toJsonArray
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.mapNotNull

/**
 * letta-mobile-wecy: chat-flavored projection of [IChannelTransport].
 *
 * Wraps the wire-level transport into the higher-level events the
 * chat ViewModels actually want to switch on:
 *
 *   - TurnStarted / TurnDone — turn-lifecycle bookends.
 *   - MessageDelta — any LettaMessage that landed (assistant /
 *     reasoning / tool_call / tool_return). The ViewModel folds these
 *     into its existing timeline state via the same dedupe pipeline
 *     that handles SSE frames today.
 *   - StopReason / UsageStatistics — bare-envelope frames the
 *     ViewModel surfaces in run-state and usage panels.
 *   - Error — soft errors (single-flight, run_not_found) and hard
 *     mid-turn errors (internal_error). Caller decides whether to
 *     retry, surface, or close.
 *   - Disconnected — terminal state for the current connection.
 *
 * The bridge owns NO timeline state — it just translates. Connection
 * lifecycle, single-flight, and run_id capture stay in
 * the platform transport; this class delegates connect/send/cancel/bye/
 * disconnect through.
 *
 * Wiring beads: w4t2 (send-path swap), a6ag (post-turn_done refetch),
 * 0vvm (backend gating).
 */
class WsChatBridge(
    private val transport: IChannelTransport,
) {
    /** Re-export the connection state without forcing callers to know about ChannelTransport. */
    val state: StateFlow<ChannelTransportState> = transport.state

    val connection: Flow<WsConnectionState> = transport.state.map { it.toConnectionState() }

    fun isConnected(): Boolean = transport.state.value is ChannelTransportState.Connected

    suspend fun awaitConnected(): WsConnectionState.Connected = transport.state
        .filter { it is ChannelTransportState.Connected }
        .map { state -> (state as ChannelTransportState.Connected).toConnectionState() }
        .first()

    /** High-level event stream tailored for chat consumers. */
    val events: Flow<WsTimelineEvent> = merge(
        transport.frameEvents.mapNotNull { it.toTimelineEvent() },
        // Surface terminal disconnects as their own event so the
        // ViewModel can show a banner / re-enable retry without
        // having to re-implement a state-collector.
        transport.state
            .filter { it is ChannelTransportState.Disconnected }
            .map { state ->
                val d = state as ChannelTransportState.Disconnected
                WsTimelineEvent.Disconnected(
                    code = d.code,
                    reason = d.reason,
                    isAuthFailure = d.isAuthFailure,
                    willReconnect = d.willReconnect,
                    reconnectAttempt = d.reconnectAttempt,
                )
            },
    )

    /** A2UI frame stream, kept separate from text/tool timeline events. */
    val a2uiEvents: Flow<A2uiFrameEvent> = transport.events.mapNotNull { frame ->
        (frame as? ServerFrame.A2ui)?.toA2uiEvent()
    }

    suspend fun connect(
        baseShimUrl: String,
        token: String,
        deviceId: String,
        clientVersion: String,
    ): Unit = transport.connect(baseShimUrl, token, deviceId, clientVersion)

    /**
     * Returns false if the connection isn't live or a turn is already
     * in flight (mirrors [IChannelTransport.send]). The caller surfaces
     * the failure as appropriate (snackbar, queued retry, etc.).
     *
     * lcp-dlj: when [attachments] is non-empty, builds a Letta
     * `content_parts` array in canonical `[text-if-any, ...images]`
     * order and sends it alongside [text]. The shim ignores [text]
     * once content_parts is present, but the field stays on the wire
     * for compatibility with older shim builds.
     */
    fun send(
        agentId: String,
        conversationId: String,
        text: String,
        otid: String? = null,
        attachments: List<com.letta.mobile.data.model.MessageContentPart.Image> = emptyList(),
        startNewConversation: Boolean = false,
    ): Boolean {
        val contentParts = if (attachments.isEmpty()) {
            null
        } else {
            buildContentParts(text, attachments).toJsonArray()
        }
        return transport.send(
            agentId = agentId,
            conversationId = conversationId,
            text = text,
            otid = otid,
            contentParts = contentParts,
            startNewConversation = startNewConversation,
        )
    }

    fun cancel(conversationId: String): Boolean = transport.cancel(conversationId)
    fun bye(): Boolean = transport.bye()
    fun sendA2uiAction(action: A2uiAction): A2uiActionDispatchResult = transport.sendA2uiAction(action)
    suspend fun disconnect(): Unit = transport.disconnect()
}

sealed interface WsConnectionState {
    data object Idle : WsConnectionState
    data object Connecting : WsConnectionState
    data class Connected(
        val a2uiEnabled: Boolean,
        val catalog: String?,
    ) : WsConnectionState
    data class Disconnected(
        val code: Int,
        val reason: String,
        val isAuthFailure: Boolean = false,
        val willReconnect: Boolean = false,
        val reconnectAttempt: Int = 0,
    ) : WsConnectionState
}

private fun ChannelTransportState.toConnectionState(): WsConnectionState = when (this) {
    is ChannelTransportState.Idle -> WsConnectionState.Idle
    is ChannelTransportState.Connecting -> WsConnectionState.Connecting
    is ChannelTransportState.Connected -> toConnectionState()
    is ChannelTransportState.Disconnected -> WsConnectionState.Disconnected(
        code = code,
        reason = reason,
        isAuthFailure = isAuthFailure,
        willReconnect = willReconnect,
        reconnectAttempt = reconnectAttempt,
    )
}

private fun ChannelTransportState.Connected.toConnectionState(): WsConnectionState.Connected =
    WsConnectionState.Connected(
        a2uiEnabled = a2uiEnabled,
        catalog = a2uiCatalog,
    )

/**
 * Higher-level event stream for chat ViewModels. Each entry maps
 * 1:1 to a [ServerFrame] except where the frame had no chat-relevant
 * payload (Welcome, Unknown — those don't appear here).
 */
sealed interface WsTimelineEvent {
    data class TurnStarted(
        val turnId: String,
        val agentId: String,
        val conversationId: String,
        // lcp-99a: shim pre-creates the Run before emitting turn_started, so
        // this is always non-null. Mobile can safely treat null upstream as a
        // shim regression.
        val runId: String,
    ) : WsTimelineEvent

    /**
     * A LettaMessage delta — assistant / reasoning / tool_call /
     * tool_return. The ViewModel folds these into its existing
     * timeline (dedupeOptimisticContentTwins + distinctBy{id} + sort
     * by date). The id prefixes (`cm-stream-`, `toolcall-`,
     * `toolreturn-`) are preserved verbatim so dedup fires correctly.
     */
    data class MessageDelta(
        val message: LettaMessage,
        // letta-mobile-ktm2b: replayed frames are silent backfill (no streaming UI).
        val isReplay: Boolean = false,
        // letta-mobile-sfex6: the frame's OWN scope, so ingest routes strictly by
        // (agentId, conversationId) and never bleeds into the currently-viewed conv.
        val agentId: String? = null,
        val conversationId: String? = null,
    ) : WsTimelineEvent

    data class StopReason(
        val turnId: String,
        val runId: String,
        val stopReason: String,
    ) : WsTimelineEvent

    data class UsageStatistics(
        val turnId: String,
        val runId: String,
        val promptTokens: Long,
        val completionTokens: Long,
        val totalTokens: Long,
        val cachedInputTokens: Long,
        val reasoningTokens: Long,
    ) : WsTimelineEvent

    data class TurnDone(
        val turnId: String,
        val runId: String,
        val status: String, // "completed" | "cancelled" | "failed"
        // lcp-srk: lossy=true when the shim dropped at least one frame at
        // its backpressure gate; mobile reconciles from disk only on lossy
        // turns. dropCount is informational telemetry.
        val lossy: Boolean = false,
        val dropCount: Long = 0L,
    ) : WsTimelineEvent

    data class SubscribeDone(
        val runId: String,
        val lastSeq: Long,
        val status: String,
    ) : WsTimelineEvent

    data class Error(
        val code: String,
        val message: String,
        val conversationId: String? = null,
        val turnId: String? = null,
        val runId: String? = null,
        val afterSeq: Long? = null,
        val oldestSeq: Long? = null,
        val lastSeq: Long? = null,
    ) : WsTimelineEvent

    data class Disconnected(
        val code: Int,
        val reason: String,
        val isAuthFailure: Boolean = false,
        val willReconnect: Boolean = false,
        val reconnectAttempt: Int = 0,
    ) : WsTimelineEvent

    data class GoalsUpdated(
        val reason: String,
        val at: String,
    ) : WsTimelineEvent

    data class AgentUpdated(
        val agentId: String,
        val reason: String,
        val at: String,
    ) : WsTimelineEvent

    data class UserActionOutcome(
        val frameId: String,
        val outcome: String,
        val actionId: String?,
        val reason: String?,
        val idempotent: Boolean,
        val agentId: String?,
        val conversationId: String?,
        val turnId: String?,
        val runId: String?,
    ) : WsTimelineEvent
}

private fun TransportFrameEvent.toTimelineEvent(): WsTimelineEvent? = frame.toTimelineEvent(isReplay)

private fun ServerFrame.toTimelineEvent(isReplay: Boolean = false): WsTimelineEvent? = when (this) {
    is ServerFrame.TurnStarted -> WsTimelineEvent.TurnStarted(
        turnId = turnId,
        agentId = agentId,
        conversationId = conversationId,
        runId = runId,
    )
    is ServerFrame.TurnDone -> WsTimelineEvent.TurnDone(
        turnId = turnId,
        runId = runId,
        status = status,
        lossy = lossy,
        dropCount = dropCount,
    )
    is ServerFrame.StopReason -> WsTimelineEvent.StopReason(
        turnId = turnId.orEmpty(), runId = runId.orEmpty(), stopReason = stopReason,
    )
    is ServerFrame.UsageStatistics -> WsTimelineEvent.UsageStatistics(
        turnId = turnId.orEmpty(),
        runId = runId.orEmpty(),
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        cachedInputTokens = cachedInputTokens,
        reasoningTokens = reasoningTokens,
    )
    is ServerFrame.Error -> WsTimelineEvent.Error(
        code = code,
        message = message,
        conversationId = conversationId,
        turnId = turnId,
        runId = runId,
        afterSeq = afterSeq,
        oldestSeq = oldestSeq,
        lastSeq = lastSeq,
    )
    is ServerFrame.GoalsUpdated -> WsTimelineEvent.GoalsUpdated(
        reason = reason,
        at = at,
    )
    is ServerFrame.AgentUpdated -> WsTimelineEvent.AgentUpdated(
        agentId = agentId,
        reason = reason,
        at = at,
    )
    is ServerFrame.UserActionOutcome -> WsTimelineEvent.UserActionOutcome(
        frameId = frameId,
        outcome = outcome,
        actionId = actionId,
        reason = reason,
        idempotent = idempotent,
        agentId = agentId,
        conversationId = conversationId,
        turnId = turnId,
        runId = runId,
    )
    // letta-mobile-y70m0: the shim's self-todo synthesized frame
    // (admin-shim buildSelfTodoFrame) is a `tool_call_message`
    // (name=TodoWrite) carrying constant per-conversation sentinel ids
    // (run_id/turn_id/tool_call_id all prefixed `selftodo-`). It is CHIP
    // DATA ONLY — consumed by SelfTodoRepository off the raw
    // `transport.events` stream for the self-todo chip — and must NOT
    // enter the chat timeline. Because it is re-emitted on every
    // TaskCreate/TaskUpdate and on resubscribe, folding it into the
    // message list produced multiple run blocks sharing the identical
    // constant run-key `run-selftodo-run-<conv>`, crashing the LazyColumn
    // with a duplicate-key IllegalArgumentException. Skip it here (the
    // single point where timeline messages are built) so the chip still
    // updates but no timeline run block is created.
    is ServerFrame.ToolCallMessage -> if (isSelfTodoChipFrame()) {
        null
    } else {
        WsFrameMapper.toLettaMessage(this)?.let {
            WsTimelineEvent.MessageDelta(it, isReplay = isReplay, agentId = agentId, conversationId = conversationId)
        }
    }
    is ServerFrame.UserMessage,
    is ServerFrame.AssistantMessage,
    is ServerFrame.ReasoningMessage,
    is ServerFrame.ToolReturnMessage -> WsFrameMapper.toLettaMessage(this)?.let {
        WsTimelineEvent.MessageDelta(
            message = it,
            isReplay = isReplay,
            agentId = messageAgentId(),
            conversationId = messageConversationId(),
        )
    }
    // Welcome carries connection metadata, not chat content; surface via state.
    // A2UI frames / capabilities / acks / Unknown are silent for chat consumers.
    // Cron frames (letta-mobile-d52f.1) are observed directly off
    // ChannelTransport.events by the cron repository — not chat content.
    is ServerFrame.Welcome,
    is ServerFrame.A2ui,
    is ServerFrame.A2uiCapabilities,
    is ServerFrame.UserActionAck,
    is ServerFrame.CronListResponse,
    is ServerFrame.CronAddResponse,
    is ServerFrame.CronGetResponse,
    is ServerFrame.CronDeleteResponse,
    is ServerFrame.CronDeleteAllResponse,
    is ServerFrame.CronsUpdated,
    is ServerFrame.AgentUpdated,
    // letta-mobile-73o2h: active-subagent frames route to the
    // SubagentRepository (active-bar), not chat content.
    is ServerFrame.SubagentListResponse,
    is ServerFrame.SubagentTodosResponse,
    is ServerFrame.SubagentsUpdated,
    // letta-mobile-2rkdj: subscribe wrappers don't surface to chat
    // directly — the inner BridgeFrame is unwrapped and re-routed
    // through the normal handler upstream of this mapper, so by the
    // time we'd see one here it's already been handled. SubscribeDone
    // remains visible as a resume terminal fallback when no turn_done
    // arrived in the replay.
    is ServerFrame.SubscribeFrameMessage -> null
    is ServerFrame.SubscribeDone -> WsTimelineEvent.SubscribeDone(
        runId = runId,
        lastSeq = lastSeq,
        status = status,
    )
    is ServerFrame.Unknown -> null
}

private fun ServerFrame.messageAgentId(): String? = when (this) {
    is ServerFrame.UserMessage -> agentId
    is ServerFrame.AssistantMessage -> agentId
    is ServerFrame.ReasoningMessage -> agentId
    is ServerFrame.ToolCallMessage -> agentId
    is ServerFrame.ToolReturnMessage -> agentId
    else -> null
}

private fun ServerFrame.messageConversationId(): String? = when (this) {
    is ServerFrame.UserMessage -> conversationId
    is ServerFrame.AssistantMessage -> conversationId
    is ServerFrame.ReasoningMessage -> conversationId
    is ServerFrame.ToolCallMessage -> conversationId
    is ServerFrame.ToolReturnMessage -> conversationId
    else -> null
}

/**
 * letta-mobile-y70m0: the self-todo sentinel prefix the shim stamps onto
 * the synthesized self-todo frame's run/turn/tool-call ids
 * (`selftodo-run-<conv>`, `selftodo-turn-<conv>`, `selftodo-<conv>`).
 * These ids are constant per conversation, so the frame is chip data only
 * and must never become a timeline run block.
 */
internal const val SELF_TODO_SENTINEL_PREFIX = "selftodo-"

/**
 * letta-mobile-y70m0: true when this `tool_call_message` is the shim's
 * synthesized self-todo chip frame rather than a real, timeline-bound
 * tool call. Identified robustly by the `selftodo-` sentinel prefix on the
 * frame's run/turn/tool-call ids (any one is sufficient — they are emitted
 * together). This predicate gates the MESSAGE-LIST / timeline path ONLY;
 * SelfTodoRepository subscribes to the raw `transport.events` stream and is
 * unaffected, so the chip keeps updating.
 *
 * The match is intentionally id-prefix based so it works against the
 * already-deployed shim. A future explicit marker field on the frame would
 * be additive — this prefix check stays as the source of truth.
 */
internal fun ServerFrame.ToolCallMessage.isSelfTodoChipFrame(): Boolean {
    if (runId.startsWith(SELF_TODO_SENTINEL_PREFIX)) return true
    if (turnId.startsWith(SELF_TODO_SENTINEL_PREFIX)) return true
    val calls = buildList {
        toolCall?.let { add(it) }
        toolCalls?.let { addAll(it) }
    }
    return calls.any { it.toolCallId.startsWith(SELF_TODO_SENTINEL_PREFIX) }
}

private fun ServerFrame.A2ui.toA2uiEvent(): A2uiFrameEvent = A2uiFrameEvent(
    transport = "admin-shim",
    frameId = id,
    timestamp = ts,
    agentId = agentId,
    conversationId = conversationId,
    turnId = turnId,
    runId = runId,
    requestId = requestId,
    messages = messages,
)
