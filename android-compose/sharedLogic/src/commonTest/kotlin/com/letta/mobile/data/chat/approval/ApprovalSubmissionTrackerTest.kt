package com.letta.mobile.data.chat.approval

import kotlin.test.Test
import kotlin.test.assertEquals

class ApprovalSubmissionTrackerTest {
    @Test
    fun answerStaysInFlightUntilTheTimelineStopsShowingItPending() {
        val tracker = ApprovalSubmissionTracker()
        tracker.begin("req-1", "conv-a")

        assertEquals(emptySet(), tracker.reconcile("conv-a", pendingRequestIds = setOf("req-1")))
        assertEquals(setOf("req-1"), tracker.submitting.value)

        assertEquals(setOf("req-1"), tracker.reconcile("conv-a", pendingRequestIds = emptySet()))
        assertEquals(emptySet(), tracker.submitting.value)
    }

    @Test
    fun reconcileOnlyTouchesItsOwnConversation() {
        val tracker = ApprovalSubmissionTracker()
        tracker.begin("req-a", "conv-a")
        tracker.begin("req-b", "conv-b")

        tracker.reconcile("conv-a", pendingRequestIds = emptySet())

        assertEquals(setOf("req-b"), tracker.submitting.value)
    }

    @Test
    fun failedSubmitClearsImmediately() {
        val tracker = ApprovalSubmissionTracker()
        tracker.begin("req-1", "conv-a")
        tracker.clear("req-1")
        assertEquals(emptySet(), tracker.submitting.value)
    }
}
