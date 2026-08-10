package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolReturnMessage

internal data class ApprovalTimelineEvidence(
    val responsesByRequestId: Map<String, List<ApprovalResponseMessage>>,
    val returnsByCallId: Map<String, List<ToolReturnMessage>>,
)

internal fun approvalTimelineEvidence(messages: List<LettaMessage>): ApprovalTimelineEvidence =
    ApprovalTimelineEvidence(
        // Deliberately NOT filtered to explicit decisions here: the Letta
        // server emits an approve=null response echo for auto-approved
        // (bypassPermissions) tool calls, and that echo is still valid
        // evidence that the request is no longer pending — see
        // hasAnyApprovalResponse. Callers that need "was this explicitly
        // approved/rejected" (e.g. the Approved/Rejected label) must apply
        // their own hasExplicitDecision filter, as hasExplicitApprovalResponse
        // does below.
        responsesByRequestId = messages.filterIsInstance<ApprovalResponseMessage>()
            .mapNotNull { response ->
                val requestId = response.approvalRequestId?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                requestId to response
            }
            .groupBy({ it.first }, { it.second }),
        returnsByCallId = messages.filterIsInstance<ToolReturnMessage>()
            .mapNotNull { returned ->
                returned.toolCallId?.takeIf(String::isNotBlank)?.let { it to returned }
            }
            .groupBy({ it.first }, { it.second }),
    )

internal fun TimelineEvent.Confirmed.hasExplicitApprovalResponse(evidence: ApprovalTimelineEvidence): Boolean {
    val requestId = approvalRequestId?.takeIf(String::isNotBlank) ?: return false
    val response = evidence.responsesByRequestId[requestId]
        .orEmpty()
        .filter(ApprovalResponseMessage::hasExplicitDecision)
        .singleOrNull()
        ?: return false
    return response.runId.isCompatibleRun(runId)
}

/**
 * True if ANY approval-response echo exists for this request on a
 * compatible run — including the approve=null echo the Letta server sends
 * for auto-approved (bypassPermissions) tool calls. Unlike
 * [hasExplicitApprovalResponse], this does NOT require an explicit
 * approve/reject decision: an auto-approval echo still proves the request
 * is resolved, it just carries no approved/rejected label to render. Used
 * only to decide whether the approval-request CARD should still be shown
 * (approvalDecided) — never to decide the Approved/Rejected label, which
 * must keep requiring an explicit decision (see ApprovalResponseCard).
 *
 * Without this, a tool call auto-approved live (which never persists a
 * pending-approval draft — see AppServerTurnEngine's letta-mobile
 * toolchip-live suppression) could still resurface a stale, separate
 * approval-request card on the next history hydration if the matching
 * tool-return evidence fails to line up (id/run mismatch, not yet synced),
 * because approve=null echoes were being discarded as "no evidence at all."
 */
internal fun TimelineEvent.Confirmed.hasAnyApprovalResponse(evidence: ApprovalTimelineEvidence): Boolean {
    val requestId = approvalRequestId?.takeIf(String::isNotBlank) ?: return false
    return evidence.responsesByRequestId[requestId]
        .orEmpty()
        .any { it.runId.isCompatibleRun(runId) }
}

internal fun TimelineEvent.Confirmed.matchingToolReturns(
    evidence: ApprovalTimelineEvidence,
): List<Pair<String, ToolReturnMessage>> = toolCalls.mapNotNull { call ->
    val callId = call.effectiveId.takeIf(String::isNotBlank) ?: return@mapNotNull null
    val returned = evidence.returnsByCallId[callId]
        .orEmpty()
        .singleOrNull { returnMessage -> returnMessage.runId.isCompatibleRun(runId) }
        ?: return@mapNotNull null
    callId to returned
}

internal fun TimelineEvent.Confirmed.allApprovalCallsReturned(
    matchingReturns: List<Pair<String, ToolReturnMessage>>,
): Boolean = approvalRequestId != null &&
    toolCalls.isNotEmpty() &&
    toolCalls.all { it.effectiveId.isNotBlank() } &&
    matchingReturns.mapTo(mutableSetOf()) { it.first }.size == toolCalls.size

internal fun TimelineEvent.Confirmed.willCompleteWith(returnedCallId: String): Boolean =
    willCompleteWith(setOf(returnedCallId))

internal fun TimelineEvent.Confirmed.willCompleteWith(returnedCallIds: Set<String>): Boolean =
    if (approvalRequestId == null) true else toolCalls.isNotEmpty() && toolCalls.all { call ->
        val callId = call.effectiveId
        callId.isNotBlank() && (callId in returnedCallIds || callId in toolReturnContentByCallId)
    }

internal fun TimelineEvent.Confirmed.matchesApprovalResponse(response: ApprovalResponseMessage): Boolean =
    response.hasExplicitDecision() && approvalRequestId == response.approvalRequestId &&
        (runId.isNullOrBlank() || response.runId.isNullOrBlank() || runId == response.runId)

internal fun Timeline.matchingApprovalEvent(response: ApprovalResponseMessage): TimelineEvent.Confirmed? =
    events.filterIsInstance<TimelineEvent.Confirmed>().filter { it.matchesApprovalResponse(response) }.singleOrNull()

internal fun TimelineEvent.Confirmed.takeMatchingPendingReturns(
    pendingReturns: MutableMap<String, ToolReturnMessage>,
): List<Pair<String, ToolReturnMessage>> = toolCalls.mapNotNull { call ->
    val callId = call.effectiveId.takeIf(String::isNotBlank) ?: return@mapNotNull null
    val returned = pendingReturns[callId]?.takeIf { it.runId.isCompatibleRun(runId) } ?: return@mapNotNull null
    pendingReturns.remove(callId)
    callId to returned
}

private fun ApprovalResponseMessage.hasExplicitDecision(): Boolean =
    approve != null || approvals.orEmpty().any { it.approve != null }

private fun String?.isCompatibleRun(requestRunId: String?): Boolean {
    val evidenceRun = takeUnless(String?::isNullOrBlank)
    val expectedRun = requestRunId.takeUnless(String?::isNullOrBlank)
    return evidenceRun == null || expectedRun == null || evidenceRun == expectedRun
}
