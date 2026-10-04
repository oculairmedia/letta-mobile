package com.letta.mobile.data.transport

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
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
 * Frames without a LettaMessage analogue (Welcome, Error,
 * TurnStarted, TurnDone, StopReason, UsageStatistics, Unknown) return
 * null — the caller routes those to non-timeline state machines
 * (run state, error banner, usage tally, etc.).
 */
object WsFrameMapper {
    fun toLettaMessage(frame: ServerFrame): LettaMessage? = when (frame) {
        is ServerFrame.UserMessage -> frame.toModel()
        is ServerFrame.AssistantMessage -> frame.toModel()
        is ServerFrame.ReasoningMessage -> frame.toModel()
        is ServerFrame.ToolCallMessage -> frame.toLettaToolMessage()
        is ServerFrame.ToolReturnMessage -> frame.toModel()

        is ServerFrame.Welcome,
        is ServerFrame.Error,
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
        is ServerFrame.GoalsUpdated,
        is ServerFrame.AgentUpdated,
        // letta-mobile-lks7m: conversation pushes drive the conversation lists, not the timeline.
        is ServerFrame.ConversationUpdated,
        // letta-mobile-73o2h: active-subagent registry frames are
        // routing-only — they drive the SubagentRepository state machine
        // (active-bar), not the chat timeline. Same treatment as crons.
        is ServerFrame.SubagentListResponse,
        is ServerFrame.SubagentTodosResponse,
        is ServerFrame.SubagentsUpdated,
        // letta-mobile-2rkdj: subscribe envelopes are routing-only —
        // SubscribeFrameMessage's inner BridgeFrame is re-routed
        // through the transport's live handler, and
        // SubscribeDone is metadata for cursor cleanup.
        is ServerFrame.SubscribeFrameMessage,
        is ServerFrame.SubscribeDone,
        // letta-mobile-1n5py.1: queue state for the send coordinator, not timeline content.
        is ServerFrame.TurnQueued,
        is ServerFrame.Unknown -> null
    }

    // letta-mobile-utw4u: pass `contentRaw` verbatim so a multimodal `content_parts` array survives the
    // wire-frame -> model hop and [extractAttachments] can pull the image base64 out at the projector.
    // Falls back to the text projection for legacy frames that only set [content].
    private fun ServerFrame.UserMessage.toModel() = UserMessage(
        id = id,
        contentRaw = contentRaw ?: JsonPrimitive(content),
        date = ts,
        runId = runId,
        otid = otid,
        seqId = seqId ?: seq.toSeqId(),
    ).stamped(logicalMessageId, turnId)

    // The wire shape carries content as a bare string; the model's `contentRaw` accepts JsonElement.
    private fun ServerFrame.AssistantMessage.toModel() = AssistantMessage(
        id = id,
        contentRaw = JsonPrimitive(content),
        date = ts,
        runId = runId,
        otid = otid,
        seqId = seqId ?: seq.toSeqId(),
    ).stamped(logicalMessageId, turnId, textSeq)

    private fun ServerFrame.ReasoningMessage.toModel() = ReasoningMessage(
        id = id,
        reasoning = reasoning,
        date = ts,
        runId = runId,
        signature = signature,
        seqId = seqId ?: seq.toSeqId(),
    ).stamped(logicalMessageId, turnId, textSeq)

    private fun ServerFrame.ToolReturnMessage.toModel() = ToolReturnMessage(
        id = id,
        toolCallId = toolCallId,
        status = status,
        stdout = stdout,
        stderr = stderr,
        toolReturnRaw = toolReturn,
        date = ts,
        runId = runId,
        seqId = seq.toSeqId(),
    ).stamped(logicalMessageId, turnId)

    private fun ToolCallPayload.toModel(): ToolCall = ToolCall(
        id = toolCallId,
        toolCallId = toolCallId,
        name = name,
        // The wire is the last place the extra encoding layer can be recognised for what it is;
        // past here the arguments are stored verbatim and every reader has to guess.
        arguments = ToolArgumentsNormalizer.normalize(arguments),
    )

    private fun ServerFrame.ToolCallMessage.toLettaToolMessage(): LettaMessage {
        val call = ToolCallMessage(
            id = id,
            toolCall = toolCall?.toModel(),
            toolCalls = toolCalls?.map { it.toModel() },
            date = ts,
            runId = runId,
            seqId = seq.toSeqId(),
        )
        return if (type == "approval_request_message") asApprovalRequestFrom(call) else call.stamped(logicalMessageId, turnId)
    }

    private fun ServerFrame.ToolCallMessage.asApprovalRequestFrom(call: ToolCallMessage) = ApprovalRequestMessage(
        id = call.id, toolCall = call.toolCall, toolCalls = call.toolCalls, date = call.date, runId = call.runId,
        seqId = call.seqId,
    ).stamped(logicalMessageId, turnId)

    /** The wire stamp (letta-mobile-ys9it) is applied in one place, whatever the row type. */
    private fun LettaMessage.stamped(logicalId: String?, turn: String?, seq: Int? = null): LettaMessage =
        when (this) {
            is UserMessage -> copy(logicalMessageId = logicalId, turnId = turn)
            is AssistantMessage -> copy(logicalMessageId = logicalId, turnId = turn, textSeq = seq)
            is ReasoningMessage -> copy(logicalMessageId = logicalId, turnId = turn, textSeq = seq)
            is ToolCallMessage -> copy(logicalMessageId = logicalId, turnId = turn)
            is ToolReturnMessage -> copy(logicalMessageId = logicalId, turnId = turn)
            is ApprovalRequestMessage -> copy(logicalMessageId = logicalId, turnId = turn)
            else -> this
        }

    private fun Long?.toSeqId(): Int? =
        this?.takeIf { it in 0L..Int.MAX_VALUE.toLong() }?.toInt()
}
