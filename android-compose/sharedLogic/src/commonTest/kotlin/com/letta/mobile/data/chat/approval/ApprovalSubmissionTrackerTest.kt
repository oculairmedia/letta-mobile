package com.letta.mobile.data.chat.approval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApprovalSubmissionTrackerTest {
    @Test
    fun answerStaysInFlightUntilTheTimelineStopsShowingItPending() {
        val tracker = ApprovalSubmissionTracker()
        tracker.begin("req-1", "conv-a")

        assertEquals(emptySet(), tracker.reconcile("conv-a", pendingRequestIds = setOf("req-1")))
        assertTrue(tracker.isSubmitting("req-1"))

        assertEquals(setOf("req-1"), tracker.reconcile("conv-a", pendingRequestIds = emptySet()))
        assertFalse(tracker.isSubmitting("req-1"))
        assertNull(tracker.latest)
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

    @Test
    fun latestIsTheMostRecentStillInFlight() {
        val tracker = ApprovalSubmissionTracker()
        tracker.begin("req-1", "conv-a")
        tracker.begin("req-2", "conv-a")
        assertEquals("req-2", tracker.latest)
        tracker.clear("req-2")
        assertEquals("req-1", tracker.latest)
    }
}
