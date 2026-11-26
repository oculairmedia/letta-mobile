package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.ToolReturnMessage
import kotlinx.collections.immutable.persistentListOf

/** An approval answer resolves the one request it names; a repeat of a decided one changes nothing. */
internal fun TimelineReducerInput.reduceApprovalResponse(response: ApprovalResponseMessage): TimelineReducerOutput {
    if (response.approvalRequestId == null) return unchanged()
    val request = prev.matchingApprovalEvent(response)?.takeUnless { it.approvalDecided } ?: return unchanged()
    val decided = request.copy(
        approvalDecided = true,
        approvalDecision = response.approvalOutcome() ?: request.approvalDecision,
    )
    return ingested(prev.replaceByServerId(decided), request, pendingToolReturnsByCallId, null)
}

/**
 * A tool return joins the row that lists its call: the row named `tc-<tool_call_id>`, or the row of a
 * multi-call message named after its first call. One that arrives before any row lists its call is
 * parked by call id and attached when a row appends or is updated to list it.
 */
internal fun TimelineReducerInput.reduceToolReturn(returned: ToolReturnMessage): TimelineReducerOutput {
    val callId = returned.toolCallId?.takeIf { it.isNotBlank() } ?: return unchanged()
    val call = prev.rowListing(callId) ?: return parked(callId, returned)
    val answered = call.answeredBy(callId, returned)
    return ingested(
        prev.replaceByLogicalId(answered),
        call,
        pendingToolReturnsByCallId,
        returned.notificationFor(call, answered),
    )
}

/**
 * The row that lists [call]. The row named after it (`tc-<id>`) is tried first; a later
 * call of the same message, or a row named otherwise (an approval request), is found by walking back
 * from the tail without allocating: a live call sits a few rows from the end, and only a call no row
 * lists walks the whole resident window.
 */
private fun Timeline.rowListing(call: String): TimelineEvent.Confirmed? {
    findByLogicalId("tc-$call")?.let { return it }
    for (i in events.indices.reversed()) {
        val row = events[i] as? TimelineEvent.Confirmed ?: continue
        if (row.toolCalls.any { it.effectiveId == call }) return row
    }
    return null
}

private fun TimelineReducerInput.parked(callId: String, returned: ToolReturnMessage): TimelineReducerOutput =
    TimelineReducerOutput(
        next = prev,
        updatedPendingToolReturnsByCallId = pendingToolReturnsByCallId.put(callId, returned),
        emittedEvents = persistentListOf(),
        notification = null,
    )

private fun TimelineEvent.Confirmed.answeredBy(callId: String, returned: ToolReturnMessage): TimelineEvent.Confirmed {
    val isError = returned.isErr == true || returned.status == "error"
    val fold = foldToolReturnBodies(toolReturnContentByCallId, toolReturnTruncationByCallId, listOf(callId to returned))
    // A return with no body still completes the call.
    val bodies = if (callId in fold.contentByCallId) fold.contentByCallId else fold.contentByCallId + (callId to "")
    val body = bodies.getValue(callId)
    return copy(
        approvalDecided = approvalDecided || willCompleteWith(callId),
        toolReturnContent = body.ifBlank { toolReturnContent ?: body },
        toolReturnIsError = isError,
        toolReturnContentByCallId = bodies.toTimelinePersistentMap(),
        toolReturnIsErrorByCallId = (toolReturnIsErrorByCallId + (callId to isError)).toTimelinePersistentMap(),
        toolReturnTruncationByCallId = fold.truncationByCallId.toTimelinePersistentMap(),
        attachments = (attachments + returned.attachments).distinct().toTimelinePersistentList(),
    )
}

private fun ToolReturnMessage.notificationFor(
    call: TimelineEvent.Confirmed,
    answered: TimelineEvent.Confirmed,
): PendingIngestNotification? =
    answered.toolReturnContent?.takeIf { messageType == "tool_return_message" && it.isNotBlank() }
        ?.let { PendingIngestNotification(call.serverId, messageType, it.take(NOTIFICATION_PREVIEW)) }

private const val NOTIFICATION_PREVIEW = 140
