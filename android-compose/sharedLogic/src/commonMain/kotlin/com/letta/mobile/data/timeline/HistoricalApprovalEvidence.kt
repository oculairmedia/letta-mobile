package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ApprovalResponseMessage
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

/** Body-free response evidence; reasons, stdout and tool payloads are not retained. */
internal class HistoricalApprovalEvidence private constructor(
    private val responses: PersistentMap<String, PersistentList<Response>>,
) {
    fun resolutionFor(request: TimelineEvent.Confirmed): Resolution {
        val requestId = request.approvalRequestId?.takeIf(String::isNotBlank)
            ?: return Resolution(false, null)
        val matching = responses[requestId].orEmpty().filter {
                (it.runId.isNullOrBlank() || request.runId.isNullOrBlank() || it.runId == request.runId)
        }
        val decision = when {
            matching.any { it.decision == ApprovalDecision.REJECTED } -> ApprovalDecision.REJECTED
            matching.any { it.decision == ApprovalDecision.APPROVED } -> ApprovalDecision.APPROVED
            else -> null
        }
        return Resolution(matching.isNotEmpty(), decision)
    }

    data class Resolution(val resolved: Boolean, val decision: ApprovalDecision?)

    private data class Response(
        val requestId: String,
        val runId: String?,
        val decision: ApprovalDecision?,
    )

    companion object {
        fun checkpoint(responses: List<ApprovalResponseMessage>): HistoricalApprovalEvidence =
            HistoricalApprovalEvidence(
                responses.mapNotNull { response ->
                    val requestId = response.approvalRequestId?.takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                    Response(requestId, response.runId, response.approvalOutcome())
                }.groupBy { it.requestId }
                    .mapValues { (_, values) -> values.toPersistentList() }
                    .toPersistentMap(),
            )
    }
}
