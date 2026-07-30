package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolReturnMessage

data class ApprovalTerminalEvidence(
    val respondedRequestRuns: Set<Pair<String, String?>>,
    val returnedCallRuns: Set<Pair<String, String?>>,
)

data class ApprovalRequestFact(
    val requestId: String,
    val runId: String?,
    val callIds: List<String>,
)

fun approvalRequestFacts(messages: List<LettaMessage>): List<ApprovalRequestFact> =
    messages.filterIsInstance<ApprovalRequestMessage>().map { request ->
        ApprovalRequestFact(request.id, request.runId, request.effectiveToolCalls.map { it.effectiveId })
    }

fun approvalTerminalEvidence(messages: List<LettaMessage>): ApprovalTerminalEvidence = ApprovalTerminalEvidence(
    respondedRequestRuns = messages.filterIsInstance<ApprovalResponseMessage>()
        .mapNotNullTo(mutableSetOf()) { response ->
            response.approvalRequestId?.takeIf(String::isNotBlank)?.let { it to response.runId }
        },
    returnedCallRuns = messages.filterIsInstance<ToolReturnMessage>()
        .mapNotNullTo(mutableSetOf()) { returned ->
            returned.toolCallId?.takeIf(String::isNotBlank)?.let { it to returned.runId }
        },
)

/** Resolves persisted approval requests from the full accumulated message window. */
fun resolvedApprovalRequestIds(
    messages: List<LettaMessage>,
    evidence: ApprovalTerminalEvidence = approvalTerminalEvidence(messages),
): Set<String> = resolvedApprovalRequestFactIds(approvalRequestFacts(messages), evidence)

fun resolvedApprovalRequestFactIds(
    requests: List<ApprovalRequestFact>,
    evidence: ApprovalTerminalEvidence,
): Set<String> = requests.filter { it.isResolvedBy(evidence) }.mapTo(mutableSetOf(), ApprovalRequestFact::requestId)

private fun ApprovalRequestFact.isResolvedBy(evidence: ApprovalTerminalEvidence): Boolean {
    if (evidence.respondedRequestRuns.any { (requestId, responseRunId) ->
            requestId == this.requestId && responseRunId.isRunCompatibleWith(runId)
        }
    ) return true
    if (callIds.isEmpty() || callIds.any(String::isBlank)) return false
    return callIds.all { callId ->
        evidence.returnedCallRuns.any { (returnedCallId, returnRunId) ->
            returnedCallId == callId && returnRunId.isRunCompatibleWith(runId)
        }
    }
}

private fun String?.isRunCompatibleWith(requestRunId: String?): Boolean =
    isNullOrBlank() || requestRunId.isNullOrBlank() || this == requestRunId
