package com.letta.mobile.data.session

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** letta-mobile-by4vm.15: overflow recovery reconciles the canonical ledger the open chat pages from. */
class TimelineFrameCollectorOverflowReconcilerTest {
    private fun writer(appended: Int) = mockk<TimelineExternalTransportWriter> {
        coEvery { reconcileRecentMessages(any(), any(), any(), any(), any()) } returns RecentMessagesReconcileOutcome.Applied(appended)
    }

    @Test fun reconcilesThroughTheBoundConversationWriter() = runBlocking {
        val writers = CanonicalConversationWriters()
        val bound = writer(appended = 2)
        writers.register(ConversationId("conversation"), bound)

        val outcome = TimelineFrameCollectorOverflowReconciler(writers).reconcile("conversation", connectionGeneration = 5)

        assertEquals(RecentMessagesReconcileOutcome.Applied(2), outcome)
        coVerify(exactly = 1) {
            bound.reconcileRecentMessages(null, "conversation", "frame_collector_overflow", true, 5)
        }
    }

    @Test fun anUnboundConversationHasNothingToRepair() = runBlocking {
        val outcome = TimelineFrameCollectorOverflowReconciler(CanonicalConversationWriters()).reconcile("none", 1)
        assertEquals(RecentMessagesReconcileOutcome.Skipped("unbound"), outcome)
    }

    @Test fun aRetiredBindingCannotEvictItsReplacement() = runBlocking {
        val writers = CanonicalConversationWriters()
        val retired = writer(appended = 1)
        val replacement = writer(appended = 3)
        writers.register(ConversationId("conversation"), retired)
        writers.register(ConversationId("conversation"), replacement)
        writers.unregister(ConversationId("conversation"), retired)

        val outcome = TimelineFrameCollectorOverflowReconciler(writers).reconcile("conversation", 1)

        assertEquals(RecentMessagesReconcileOutcome.Applied(3), outcome)
        writers.unregister(ConversationId("conversation"), replacement)
        assertEquals(
            RecentMessagesReconcileOutcome.Skipped("unbound"),
            TimelineFrameCollectorOverflowReconciler(writers).reconcile("conversation", 1),
        )
    }
}
