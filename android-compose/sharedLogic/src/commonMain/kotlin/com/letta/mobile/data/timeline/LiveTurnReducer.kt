package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.SyntheticSkillEnvelopeDetector
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf

/**
 * Folds one streamed frame into a live turn's timeline (letta-mobile-nbha6, plan 1.4).
 *
 * A row is named by the logical id the host stamped on its frames and nothing else: the first frame
 * of an id appends the row, a later text frame REPLACES its text when it carries a higher `text_seq`
 * (the text is a cumulative snapshot, never concatenated) and is dropped otherwise, and a tool return
 * joins the `tc-<tool_call_id>` row. Content, prefixes, otids, run ids and server ids never decide
 * where a frame goes.
 */
internal fun reduceLiveFrame(input: TimelineReducerInput): TimelineReducerOutput {
    val message = input.frame
    return when {
        SyntheticSkillEnvelopeDetector.isSyntheticSkillEnvelope(message) -> input.unchanged()
        message is ApprovalResponseMessage -> input.reduceApprovalResponse(message)
        message is ToolReturnMessage -> input.reduceToolReturn(message)
        else -> input.reduceRowFrame()
    }
}

private fun TimelineReducerInput.reduceRowFrame(): TimelineReducerOutput {
    val incoming = frame.toTimelineEvent(prev.nextLocalPosition(), agentId) ?: return unchanged()
    if (frame.isUnstampedText(incoming)) return dropUnstamped(incoming)
    val existing = prev.findByLogicalId(incoming.logicalId)
    return if (existing == null) appendRow(incoming) else updateRow(existing, incoming)
}

/** An assistant or reasoning frame the host never stamped: it has no id to key a row by. */
private fun LettaMessage.isUnstampedText(row: TimelineEvent.Confirmed): Boolean =
    row.messageType.carriesStreamedText() && row.textSeq == 0 && logicalMessageId.isNullOrBlank()

private fun TimelineMessageType.carriesStreamedText(): Boolean =
    this == TimelineMessageType.ASSISTANT || this == TimelineMessageType.REASONING

private fun TimelineReducerInput.dropUnstamped(row: TimelineEvent.Confirmed): TimelineReducerOutput {
    Telemetry.event(
        "LiveTurnReducer", "live.unstampedFrame",
        "messageType" to row.messageType.name,
        "serverId" to row.serverId,
        "conversationId" to prev.conversationId,
        level = Telemetry.Level.WARN,
    )
    return unchanged()
}

private fun TimelineReducerInput.appendRow(incoming: TimelineEvent.Confirmed): TimelineReducerOutput {
    val pending = LinkedHashMap(pendingToolReturnsByCallId)
    val row = applyPendingToolReturns(incoming, pending)
    val next = prev.append(row).copy(liveCursor = row.serverId)
    return ingested(next, row, pending.toTimelinePersistentMap(), row.appendNotification())
}

private fun TimelineEvent.Confirmed.appendNotification(): PendingIngestNotification? =
    if (messageType != TimelineMessageType.ASSISTANT) null
    else PendingIngestNotification(serverId, "assistant_message", content.take(PREVIEW_LENGTH).ifBlank { null })

private fun TimelineReducerInput.updateRow(
    existing: TimelineEvent.Confirmed,
    incoming: TimelineEvent.Confirmed,
): TimelineReducerOutput {
    val updated = existing.advancedBy(incoming) ?: return dropStale(existing, incoming)
    val next = prev.replaceByLogicalId(updated).copy(liveCursor = updated.serverId)
    return ingested(next, updated, pendingToolReturnsByCallId, null)
}

/** The row after [incoming], or null when [incoming] is not newer than what the row already holds. */
private fun TimelineEvent.Confirmed.advancedBy(incoming: TimelineEvent.Confirmed): TimelineEvent.Confirmed? =
    when {
        incoming.messageType.carriesStreamedText() ->
            incoming.takeIf { it.textSeq > textSeq }?.copy(position = position)
        else -> withRicherToolCalls(incoming)
    }

/**
 * A tool call is emitted more than once (the second copy can carry empty arguments); it moves
 * forward only when the copy holds more argument text than the row, and keeps what the row has
 * already learned about its return and approval.
 */
private fun TimelineEvent.Confirmed.withRicherToolCalls(incoming: TimelineEvent.Confirmed): TimelineEvent.Confirmed? {
    if (messageType != TimelineMessageType.TOOL_CALL || incoming.argumentLength() <= argumentLength()) return null
    return copy(
        content = incoming.content,
        toolCalls = incoming.toolCalls,
        approvalRequestId = incoming.approvalRequestId ?: approvalRequestId,
        seqId = latestSeqId(seqId, incoming.seqId),
    )
}

private fun TimelineEvent.Confirmed.argumentLength(): Int = toolCalls.sumOf { it.arguments?.length ?: 0 }

private fun TimelineReducerInput.dropStale(
    existing: TimelineEvent.Confirmed,
    incoming: TimelineEvent.Confirmed,
): TimelineReducerOutput {
    if (incoming.messageType.carriesStreamedText()) {
        Telemetry.event(
            "LiveTurnReducer", "live.staleTextFrame",
            "logicalId" to existing.logicalId,
            "heldTextSeq" to existing.textSeq,
            "incomingTextSeq" to incoming.textSeq,
            "conversationId" to prev.conversationId,
            level = Telemetry.Level.WARN,
        )
    }
    return unchanged()
}

internal fun TimelineReducerInput.unchanged(): TimelineReducerOutput =
    TimelineReducerOutput(prev, pendingToolReturnsByCallId, persistentListOf(), null)

internal fun TimelineReducerInput.ingested(
    next: Timeline,
    row: TimelineEvent.Confirmed,
    pending: PersistentMap<String, ToolReturnMessage>,
    notification: PendingIngestNotification?,
): TimelineReducerOutput = TimelineReducerOutput(
    next = next,
    updatedPendingToolReturnsByCallId = pending,
    emittedEvents = persistentListOf(TimelineSyncEvent.StreamEventIngested(row.serverId, frame.messageType)),
    notification = notification,
)

private const val PREVIEW_LENGTH = 140
