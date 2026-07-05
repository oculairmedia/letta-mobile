package com.letta.mobile.data.timeline

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-h30cy: the dual-ingest guard is now shared by conversationId, so
 * TWO TimelineWsSubscription instances (the two loop instances that existed for
 * one Iroh conversation) see the SAME active state — the coordinator path marking
 * active on instance A stops the stream-subscriber path on instance B.
 */
class TimelineExternalTransportRegistryTest {

    @Test
    fun `markActive on one subscription is visible to another for the same conversation`() {
        val conv = "conv-h30cy-shared"
        TimelineExternalTransportRegistry.clear(conv)
        val coordinatorPath = TimelineWsSubscription(conv)
        val streamSubscriberPath = TimelineWsSubscription(conv)

        assertFalse(streamSubscriberPath.isActive())
        // coordinator (external-transport) path claims the conversation
        coordinatorPath.markActive()
        // the OTHER instance now sees it active -> its submitStreamEvent skips
        assertTrue(streamSubscriberPath.isActive(), "guard must be shared across loop instances")

        coordinatorPath.clear()
        assertFalse(streamSubscriberPath.isActive())
    }

    @Test
    fun `distinct conversations do not share active state`() {
        TimelineExternalTransportRegistry.clear("a")
        TimelineExternalTransportRegistry.clear("b")
        TimelineWsSubscription("a").markActive()
        assertTrue(TimelineWsSubscription("a").isActive())
        assertFalse(TimelineWsSubscription("b").isActive())
        TimelineExternalTransportRegistry.clear("a")
    }
}
