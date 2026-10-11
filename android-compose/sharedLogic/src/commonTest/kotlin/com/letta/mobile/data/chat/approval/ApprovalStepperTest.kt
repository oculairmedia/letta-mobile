package com.letta.mobile.data.chat.approval

import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalResponse
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.runtime.PendingApprovalDetails
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** letta-mobile-bzvro.14 (F14, G47): "1 of N" stepping through parallel approvals, submitted together. */
class ApprovalStepperTest {
    private fun request(id: String) = UiApprovalRequest(
        requestId = "perm-$id",
        toolCalls = listOf(UiApprovalToolCall(id, "Bash", "{}")),
        details = PendingApprovalDetails("perm-$id", id, "Bash"),
    )

    private fun message(request: UiApprovalRequest) =
        UiMessage(id = "m-${request.requestId}", role = "assistant", content = "", timestamp = "t", approvalRequest = request)

    private fun decision(id: String, approve: Boolean = true) =
        ApprovalStepDecision(requestId = "perm-$id", toolCallIds = listOf(id), approve = approve)

    private val three = listOf(request("a"), request("b"), request("c"))

    @Test
    fun `three pending requests step one at a time and batch in request order`() {
        var steps = ApprovalStepper.reconcile(ApprovalSteps(), three)
        assertTrue(steps.stepping)
        assertEquals(1, steps.position)
        assertEquals(3, steps.total)

        steps = ApprovalStepper.decide(steps, decision("a"))
        assertEquals(2, steps.position)
        assertEquals(emptyList(), ApprovalStepper.batch(steps), "nothing is sent before the last decision")
        steps = ApprovalStepper.decide(steps, decision("b", approve = false))
        steps = ApprovalStepper.decide(steps, decision("c"))

        assertTrue(steps.ready)
        assertEquals(listOf("perm-a", "perm-b", "perm-c"), ApprovalStepper.batch(steps).map { it.requestId })
        assertEquals(listOf(true, false, true), ApprovalStepper.batch(steps).map { it.approve })
    }

    @Test
    fun `stepping back revisits a decision and forward needs a decision first`() {
        var steps = ApprovalStepper.reconcile(ApprovalSteps(), three)
        assertFalse(steps.canGoForward())
        steps = ApprovalStepper.decide(steps, decision("a"))
        steps = ApprovalStepper.back(steps)
        assertEquals(1, steps.position)
        assertTrue(steps.canGoForward())

        steps = ApprovalStepper.decide(steps, decision("a", approve = false))
        assertEquals(2, steps.position, "re-deciding moves on to the next undecided request")
        assertEquals(false, steps.decided.getValue("perm-a").approve)
    }

    @Test
    fun `a request answered elsewhere leaves the steps with its held decision`() {
        var steps = ApprovalStepper.reconcile(ApprovalSteps(), three)
        steps = ApprovalStepper.decide(steps, decision("a"))

        steps = ApprovalStepper.reconcile(steps, three.drop(1))

        assertEquals(2, steps.total)
        assertEquals(emptyMap(), steps.decided, "a stale decision is never sent")
        assertEquals("perm-b", steps.currentRequestId)
    }

    @Test
    fun `a lone request is not stepped`() {
        val steps = ApprovalStepper.reconcile(ApprovalSteps(), three.take(1))
        assertFalse(steps.stepping)
        assertFalse(ApprovalStepper.decide(steps, decision("a")).ready)
    }

    @Test
    fun `pending requests come from the timeline oldest first without answered ones`() {
        val answered = message(request("a"))
        val response = UiMessage(id = "r", role = "user", content = "", timestamp = "t", approvalResponse = UiApprovalResponse(requestId = "perm-a"))
        val plain = UiMessage(
            id = "p", role = "assistant", content = "", timestamp = "t",
            approvalRequest = UiApprovalRequest("auto", listOf(UiApprovalToolCall("x", "Bash", "{}"))),
        )

        val pending = ApprovalStepper.pendingIn(listOf(answered, response, message(request("b")), plain, message(request("c"))))

        assertEquals(listOf("perm-b", "perm-c"), pending.map { it.requestId }, "auto-resolved requests never step")
    }
}
