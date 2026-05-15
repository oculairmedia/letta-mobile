package com.letta.mobile.data.transport

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.buildContentParts
import com.letta.mobile.data.model.toJsonArray
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.mapNotNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * letta-mobile-wecy: chat-flavored projection of [ChannelTransport].
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
 * [ChannelTransport]; this class delegates connect/send/cancel/bye/
 * disconnect through.
 *
 * Wiring beads: w4t2 (send-path swap), a6ag (post-turn_done refetch),
 * 0vvm (backend gating).
 */
@Singleton
class WsChatBridge @Inject constructor(
    private val transport: ChannelTransport,
) {
    /** Re-export the connection state without forcing callers to know about ChannelTransport. */
    val state: StateFlow<ChannelTransport.State> = transport.state

    /** High-level event stream tailored for chat consumers. */
    val events: Flow<WsTimelineEvent> = merge(
        transport.events.mapNotNull { it.toTimelineEvent() },
        // Surface terminal disconnects as their own event so the
        // ViewModel can show a banner / re-enable retry without
        // having to re-implement a state-collector.
        transport.state
            .filter { it is ChannelTransport.State.Disconnected }
            .map { state ->
                val d = state as ChannelTransport.State.Disconnected
                WsTimelineEvent.Disconnected(d.code, d.reason)
            },
    )

    suspend fun connect(
        baseShimUrl: String,
        token: String,
        deviceId: String,
        clientVersion: String,
    ): Unit = transport.connect(baseShimUrl, token, deviceId, clientVersion)

    /**
     * Returns false if the connection isn't live or a turn is already
     * in flight (mirrors [ChannelTransport.send]). The caller surfaces
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
    ): Boolean {
        val contentParts = if (attachments.isEmpty()) {
            null
        } else {
            buildContentParts(text, attachments).toJsonArray()
        }
        return transport.send(agentId, conversationId, text, otid, contentParts)
    }

    fun cancel(): Boolean = transport.cancel()
    fun bye(): Boolean = transport.bye()
    suspend fun disconnect(): Unit = transport.disconnect()
}

/**
 * Higher-level event stream for chat ViewModels. Each entry maps
 * 1:1 to a [ServerFrame] except where the frame had no chat-relevant
 * payload (Welcome, Ping, Unknown — those don't appear here).
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
    data class MessageDelta(val message: LettaMessage) : WsTimelineEvent

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

    data class Error(
        val code: String,
        val message: String,
        val turnId: String?,
        val runId: String?,
    ) : WsTimelineEvent

    data class Disconnected(val code: Int, val reason: String) : WsTimelineEvent
}

private fun ServerFrame.toTimelineEvent(): WsTimelineEvent? = when (this) {
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
        turnId = turnId, runId = runId, stopReason = stopReason,
    )
    is ServerFrame.UsageStatistics -> WsTimelineEvent.UsageStatistics(
        turnId = turnId,
        runId = runId,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        cachedInputTokens = cachedInputTokens,
        reasoningTokens = reasoningTokens,
    )
    is ServerFrame.Error -> WsTimelineEvent.Error(
        code = code,
        message = message,
        turnId = turnId,
        runId = runId,
    )
    is ServerFrame.AssistantMessage,
    is ServerFrame.ReasoningMessage,
    is ServerFrame.ToolCallMessage,
    is ServerFrame.ToolReturnMessage -> WsFrameMapper.toLettaMessage(this)?.let {
        WsTimelineEvent.MessageDelta(it)
    }
    // Welcome carries connection metadata, not chat content; surface via state.
    // Ping / Unknown are silent for chat consumers.
    is ServerFrame.Welcome,
    is ServerFrame.Ping,
    is ServerFrame.Unknown -> null
}
