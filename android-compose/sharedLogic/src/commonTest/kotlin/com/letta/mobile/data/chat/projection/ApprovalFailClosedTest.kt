package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiApprovalDecision
import com.letta.mobile.data.model.UiApprovalResponse
import com.letta.mobile.data.model.UiToolApprovalDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** letta-mobile-soa3i.4: a rejection must never read as an approval. */
class ApprovalFailClosedTest {
    @Test
    fun perCallRejectionUnderTopLevelApproveIsRejected() {
        val response = UiApprovalResponse(
            approved = true,
            approvals = listOf(UiApprovalDecision("a", approved = true), UiApprovalDecision("b", approved = false)),
        )
        assertEquals(false, response.verdict)
    }

    @Test
    fun explicitApprovalsOnlyAreApproved() {
        assertEquals(true, UiApprovalResponse(approvals = listOf(UiApprovalDecision("a", approved = true))).verdict)
    }

    @Test
    fun noExplicitDecisionHasNoVerdict() {
        assertNull(UiApprovalResponse(approved = null, approvals = listOf(UiApprovalDecision("a"))).verdict)
    }

    @Test
    fun implicitResolutionWithAnErrorReturnGetsNoChip() {
        assertNull(implicitApprovalChip(decided = true, approvalRequestId = "req", anyToolReturnError = true))
    }

    @Test
    fun cleanImplicitResolutionStaysApproved() {
        assertEquals(
            UiToolApprovalDecision.Approved,
            implicitApprovalChip(decided = true, approvalRequestId = "req", anyToolReturnError = false),
        )
    }

    @Test
    fun undecidedOrUnrequestedGetsNoChip() {
        assertNull(implicitApprovalChip(decided = false, approvalRequestId = "req", anyToolReturnError = false))
        assertNull(implicitApprovalChip(decided = true, approvalRequestId = null, anyToolReturnError = false))
    }
}
