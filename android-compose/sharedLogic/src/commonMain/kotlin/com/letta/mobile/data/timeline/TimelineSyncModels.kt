package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.EventMessage
import com.letta.mobile.data.model.HiddenReasoningMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.PingMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.StopReason
import com.letta.mobile.data.model.SystemMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UnknownMessage
import com.letta.mobile.data.model.UsageStatistics
import com.letta.mobile.data.model.UserMessage

/** Observable events emitted by the sync loop for UI/log subscribers. */
sealed class TimelineSyncEvent {
    data class Hydrated(val messageCount: Int) : TimelineSyncEvent()
    data class LocalAppended(val otid: String) : TimelineSyncEvent()
    data class LocalConfirmed(val otid: String, val serverId: String) : TimelineSyncEvent()
    data class ServerEvent(val message: LettaMessage) : TimelineSyncEvent()
    data class StreamError(val type: String, val message: String) : TimelineSyncEvent()
    /** A LettaMessage was ingested via the resume-stream subscriber (letta-mobile-mge5). */
    data class StreamEventIngested(val serverId: String, val messageType: String?) : TimelineSyncEvent()
    /** Stream subscriber successfully opened a stream; resets backoff. */
    object StreamSubscriberOpened : TimelineSyncEvent()
    /** Stream subscriber closed cleanly (run finished). */
    object StreamSubscriberClosed : TimelineSyncEvent()
    data class ReconcileError(val message: String) : TimelineSyncEvent()
    data class HydrateFailed(val message: String) : TimelineSyncEvent()
}

/**
 * Convert a server [LettaMessage] to a Confirmed timeline event.
 *
 * Returns null for message types we don't display (pings, stop reasons, etc.).
 */
fun LettaMessage.toTimelineEvent(position: Double): TimelineEvent.Confirmed? {
    val (type, text) = when (this) {
        is UserMessage -> TimelineMessageType.USER to content
        is AssistantMessage -> TimelineMessageType.ASSISTANT to content
        is ReasoningMessage -> TimelineMessageType.REASONING to reasoning
        is ToolCallMessage -> TimelineMessageType.TOOL_CALL to renderToolCallContent(effectiveToolCalls)
        is ApprovalRequestMessage -> TimelineMessageType.TOOL_CALL to renderToolCallContent(effectiveToolCalls)
        is ToolReturnMessage -> TimelineMessageType.TOOL_RETURN to (toolReturn.funcResponse ?: "")
        is SystemMessage -> TimelineMessageType.SYSTEM to content
        // letta-mobile-5s1n: server-emitted error frames render as a
        // dedicated ERROR bubble so the user gets visible feedback when a
        // run aborts mid-flight (previously absorbed into UnknownMessage
        // and silently dropped).
        is com.letta.mobile.data.model.ErrorMessage -> TimelineMessageType.ERROR to text
        else -> return null
    }
    val attachments = when (this) {
        is UserMessage -> this.attachments
        is AssistantMessage -> this.attachments
        is SystemMessage -> this.attachments
        is ToolReturnMessage -> this.attachments
        is ReasoningMessage, is ToolCallMessage, is ApprovalRequestMessage,
        is ApprovalResponseMessage, is HiddenReasoningMessage, is EventMessage,
        is PingMessage, is UnknownMessage, is StopReason, is UsageStatistics,
        is com.letta.mobile.data.model.ErrorMessage -> emptyList()
    }
    val effectiveOtid = otid ?: "server-$id-${type.name.lowercase()}"
    val date = date?.let(::parseTimelineInstantOrNull) ?: timelineNow()
    val toolCallsList = when (this) {
        is ToolCallMessage -> effectiveToolCalls
        is ApprovalRequestMessage -> effectiveToolCalls
        else -> emptyList()
    }
    val approvalId = when (this) {
        is ApprovalRequestMessage -> id
        else -> null
    }
    return TimelineEvent.Confirmed(
        position = position,
        otid = effectiveOtid,
        content = text,
        serverId = id,
        messageType = type,
        date = date,
        runId = runId,
        stepId = stepId,
        attachments = attachments,
        toolCalls = toolCallsList,
        approvalRequestId = approvalId,
        seqId = seqId,
    )
}

/**
 * Render a tool-call list for display as the Confirmed.content field.
 * Format: "name(args)" on one line per tool call. Args are the JSON-string
 * that the server sends — typically streaming concatenation results. The UI
 * bubble component parses this further for pretty-printing if desired.
 * letta-mobile-mge5: previously only the tool name was preserved, so streaming
 * argument deltas were never visible and ApprovalRequestMessages were dropped.
 */
internal fun renderToolCallContent(calls: List<com.letta.mobile.data.model.ToolCall>): String {
    if (calls.isEmpty()) return "tool_call"
    return calls.joinToString("\n") { tc ->
        val name = tc.name ?: "tool"
        val args = tc.arguments ?: ""
        if (args.isBlank()) name else "$name($args)"
    }
}

fun normalizeHydratedMessageOrder(messages: List<LettaMessage>): List<LettaMessage> {
    if (messages.size < 2) return messages
    val indexed = messages.withIndex()
    return indexed.sortedWith { left, right ->
        val l = left.value
        val r = right.value

        val seqCompare = compareNullableInts(l.seqId, r.seqId)
        if (seqCompare != 0) return@sortedWith seqCompare

        val leftDate = l.date?.let(::parseTimelineInstantOrNull)
        val rightDate = r.date?.let(::parseTimelineInstantOrNull)
        if (leftDate != null && rightDate != null) {
            val dateCompare = compareTimelineInstants(leftDate, rightDate)
            if (dateCompare != 0) return@sortedWith dateCompare
        }

        if (!l.runId.isNullOrBlank() && l.runId == r.runId) {
            val typeCompare = hydratedMessageTypePriority(l).compareTo(hydratedMessageTypePriority(r))
            if (typeCompare != 0) return@sortedWith typeCompare
        }

        left.index.compareTo(right.index)
    }.map { it.value }
}

internal fun compareNullableInts(left: Int?, right: Int?): Int = when {
    left != null && right != null && left != right -> left.compareTo(right)
    else -> 0
}

fun TimelineEvent.Confirmed.hasAlreadyIngestedStreamFrame(
    incoming: TimelineEvent.Confirmed,
): Boolean =
    messageType == TimelineMessageType.ASSISTANT &&
        incoming.messageType == TimelineMessageType.ASSISTANT &&
        seqId != null &&
        incoming.seqId != null &&
        incoming.seqId <= seqId

fun latestSeqId(left: Int?, right: Int?): Int? = when {
    left == null -> right
    right == null -> left
    else -> maxOf(left, right)
}

fun hydratedMessageTypePriority(message: LettaMessage): Int = when (message) {
    is UserMessage -> 0
    is ReasoningMessage, is HiddenReasoningMessage -> 1
    is ToolCallMessage, is ApprovalRequestMessage -> 2
    is ToolReturnMessage, is ApprovalResponseMessage -> 3
    is AssistantMessage -> 4
    else -> 5
}
