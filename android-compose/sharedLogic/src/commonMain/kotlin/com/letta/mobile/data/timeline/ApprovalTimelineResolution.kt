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
    return evidence.responsesByRequestId[requestId]
        .orEmpty()
        .singleOrNull()
        ?.runId
        .isCompatibleRun(runId)
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
    if (approvalRequestId == null) true else toolCalls.isNotEmpty() && toolCalls.all { call ->
        val callId = call.effectiveId
        callId.isNotBlank() && (callId == returnedCallId || callId in toolReturnContentByCallId)
    }

internal fun TimelineEvent.Confirmed.matchesApprovalResponse(response: ApprovalResponseMessage): Boolean =
    approvalRequestId == response.approvalRequestId &&
        !runId.isNullOrBlank() && runId == response.runId

internal fun Timeline.matchingApprovalEvent(response: ApprovalResponseMessage): TimelineEvent.Confirmed? =
    events.filterIsInstance<TimelineEvent.Confirmed>().firstOrNull { it.matchesApprovalResponse(response) }

private fun String?.isCompatibleRun(requestRunId: String?): Boolean {
    val evidenceRun = takeUnless(String?::isNullOrBlank)
    val expectedRun = requestRunId.takeUnless(String?::isNullOrBlank)
    return evidenceRun == null || expectedRun == null || evidenceRun == expectedRun
}
