package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ApprovalResponseMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoricalApprovalEvidenceTest {
    @Test
    fun checkpointMatchesReducerAfterEveryResponse() {
        val responses = mutableListOf<ApprovalResponseMessage>()
        val request = request()
        val arrivals = listOf(
            response("foreign", "other-run", false),
            response("automatic", "run", null),
            response("approved", "run", true),
            response("rejected", "run", false),
            response("late-approved", "run", true),
        )
        for (arrival in arrivals) {
            responses += arrival
            val expected = approvalTimelineEvidence(responses)
            val actual = HistoricalApprovalEvidence.checkpoint(responses).resolutionFor(request)
            assertEquals(request.hasAnyApprovalResponse(expected), actual.resolved, arrival.id)
            assertEquals(request.approvalOutcomeFromEvidence(expected), actual.decision, arrival.id)
        }
    }

    @Test
    fun checkpointDoesNotAliasMutableSourceAndPreservesImplicitResolution() {
        val responses = mutableListOf(response("automatic", "run", null))
        val checkpoint = HistoricalApprovalEvidence.checkpoint(responses)
        responses.clear()

        assertEquals(HistoricalApprovalEvidence.Resolution(true, null), checkpoint.resolutionFor(request()))
        assertEquals(
            HistoricalApprovalEvidence.Resolution(false, null),
            checkpoint.resolutionFor(request().copy(runId = "another-run")),
        )
    }

    @Test
    fun missingRunMatchesLegacyCompatibilityWithoutInventingDecision() {
        val checkpoint = HistoricalApprovalEvidence.checkpoint(listOf(response("automatic", null, null)))
        assertEquals(HistoricalApprovalEvidence.Resolution(true, null), checkpoint.resolutionFor(request()))
        assertEquals(
            HistoricalApprovalEvidence.Resolution(false, null),
            checkpoint.resolutionFor(request().copy(approvalRequestId = "different")),
        )
    }

    private fun response(id: String, runId: String?, approve: Boolean?) = ApprovalResponseMessage(
        id = id,
        approvalRequestId = "request",
        runId = runId,
        approve = approve,
        reason = "body that must not be retained",
    )

    private fun request() = TimelineEvent.Confirmed(
        position = 1.0,
        otid = "owner",
        serverId = "server",
        content = "tool",
        messageType = TimelineMessageType.TOOL_CALL,
        date = parseTimelineInstant("1970-01-01T00:00:00Z"),
        runId = "run",
        stepId = null,
        approvalRequestId = "request",
    )
}
