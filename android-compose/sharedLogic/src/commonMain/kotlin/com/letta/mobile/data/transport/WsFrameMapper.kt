package com.letta.mobile.data.transport

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import kotlinx.serialization.json.JsonPrimitive

/**
 * letta-mobile-wecy: bridge from wire-level [ServerFrame]s to the
 * existing [LettaMessage] sealed hierarchy that the chat timeline +
 * dedupe pipeline already understand.
 *
 * **Why a separate mapper layer:** the WS envelope carries routing
 * metadata (`turn_id`, `run_id`) that the LettaMessage shape doesn't
 * model directly. We pull the run_id onto the projected LettaMessage
 * (it has a slot) and drop turn_id (UI doesn't need it). The
 * `cm-stream-` prefix on assistant_message ids and `toolcall-` /
 * `toolreturn-` prefixes are preserved verbatim — mobile's
 * `dedupeOptimisticContentTwins` (content-based on assistants) and
 * `distinctBy { id }` (id-based on tools) rely on them being intact.
 *
 * Frames without a LettaMessage analogue (Welcome, Error, Ping,
 * TurnStarted, TurnDone, StopReason, UsageStatistics, Unknown) return
 * null — the caller routes those to non-timeline state machines
 * (run state, error banner, usage tally, etc.).
 */
object WsFrameMapper {
    fun toLettaMessage(frame: ServerFrame): LettaMessage? = when (frame) {
        is ServerFrame.AssistantMessage -> AssistantMessage(
            id = frame.id,
            // The wire shape carries content as a bare string; the
            // model's `contentRaw` accepts JsonElement and unwraps
            // primitive strings through extractContent.
            contentRaw = JsonPrimitive(frame.content),
            runId = frame.runId,
            otid = frame.otid,
            seqId = frame.seqId,
        )

        is ServerFrame.ReasoningMessage -> ReasoningMessage(
            id = frame.id,
            reasoning = frame.reasoning,
            runId = frame.runId,
            signature = frame.signature,
        )

        is ServerFrame.ToolCallMessage -> ToolCallMessage(
            id = frame.id,
            toolCall = frame.toolCall?.toModel(),
            toolCalls = frame.toolCalls?.map { it.toModel() },
            runId = frame.runId,
        )

        is ServerFrame.ToolReturnMessage -> ToolReturnMessage(
            id = frame.id,
            toolCallId = frame.toolCallId,
            status = frame.status,
            stdout = frame.stdout,
            stderr = frame.stderr,
            // Wrap the bare tool_return string in a JsonPrimitive so
            // the model's lazy `toolReturn` getter (which checks
            // `is JsonPrimitive && isString`) finds it.
            toolReturnRaw = frame.toolReturn?.let { JsonPrimitive(it) },
            runId = frame.runId,
        )

        is ServerFrame.Welcome,
        is ServerFrame.Error,
        is ServerFrame.Ping,
        is ServerFrame.TurnStarted,
        is ServerFrame.TurnDone,
        is ServerFrame.StopReason,
        is ServerFrame.UsageStatistics,
        is ServerFrame.A2ui,
        is ServerFrame.A2uiCapabilities,
        is ServerFrame.UserActionAck,
        is ServerFrame.UserActionOutcome,
        is ServerFrame.CronListResponse,
        is ServerFrame.CronAddResponse,
        is ServerFrame.CronGetResponse,
        is ServerFrame.CronDeleteResponse,
        is ServerFrame.CronDeleteAllResponse,
        is ServerFrame.CronsUpdated,
        // letta-mobile-2rkdj: subscribe envelopes are routing-only —
        // SubscribeFrameMessage's inner BridgeFrame is re-routed
        // through the live handler in ChannelTransport, and
        // SubscribeDone is metadata for cursor cleanup.
        is ServerFrame.SubscribeFrameMessage,
        is ServerFrame.SubscribeDone,
        is ServerFrame.Unknown -> null
    }

    private fun ToolCallPayload.toModel(): ToolCall = ToolCall(
        id = toolCallId,
        toolCallId = toolCallId,
        name = name,
        arguments = arguments,
    )
}
