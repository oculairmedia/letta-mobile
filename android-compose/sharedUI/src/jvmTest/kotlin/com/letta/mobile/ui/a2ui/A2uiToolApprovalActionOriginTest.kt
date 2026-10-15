package com.letta.mobile.ui.a2ui

import com.letta.mobile.data.a2ui.A2uiActionOrigin
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.data.a2ui.ToolApprovalRef
import com.letta.mobile.data.a2ui.toToolApprovalSubmission
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-by4vm.2: only the card's own action carries the origin the approval gate requires. */
class A2uiToolApprovalActionOriginTest {
    @Test
    fun toolApprovalCardActionCarriesCardOrigin() {
        val surface = A2uiSurfaceState(surfaceId = "surface-1", conversationId = "conv-1", approvalRequestId = "approval-1")

        val action = toolApprovalAction(surface, "call-1", ToolApprovalResult(decision = "approve", scope = "once"))

        assertEquals(A2uiActionOrigin.ToolApprovalCard, action.origin)
        assertEquals(ToolApprovalRef.of("approval-1", "call-1"), action.toToolApprovalSubmission()?.ref)
    }
}
