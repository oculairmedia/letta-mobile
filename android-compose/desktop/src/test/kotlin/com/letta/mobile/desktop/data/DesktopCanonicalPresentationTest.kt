package com.letta.mobile.desktop.data

import com.letta.mobile.data.timeline.CanonicalTimelineCoordinator
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopCanonicalPresentationTest {
    @Test fun missingTargetKeepsTailAndCloseDoesNotRetireIngestion() = runTest {
        val coordinator = CanonicalTimelineCoordinator(EmptyTimelineStore(), NoTimelineTransport)
        val owner = coordinator.acquire(TimelineScope("backend", "conversation", "agent"))
        val presentation = openDesktopCanonicalPresentation(coordinator, owner, backgroundScope, "missing")
        assertEquals("missing", presentation.missingTarget)
        assertFalse(coordinator.retire(owner))
        presentation.close()
        assertTrue(coordinator.retire(owner))
    }

    @Test fun centeredSearchMissPreservesViewportAndBackgroundFence() = runTest {
        val coordinator = CanonicalTimelineCoordinator(EmptyTimelineStore(), NoTimelineTransport)
        val owner = coordinator.acquire(TimelineScope("backend", "conversation", "agent"))
        val fence = coordinator.beginLive(owner)
        val presentation = openDesktopCanonicalPresentation(coordinator, owner, backgroundScope)
        presentation.viewport = "resident" to 12
        assertFalse(presentation.navigate("missing"))
        assertEquals("resident" to 12, presentation.viewport)
        presentation.close()
        assertFalse(coordinator.retire(owner))
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Done))
        assertTrue(coordinator.retire(owner))
    }

    @Test fun rapidOwnerSwitchRetiresOnlyTheDetachedConversation() = runTest {
        val coordinator = CanonicalTimelineCoordinator(EmptyTimelineStore(), NoTimelineTransport)
        val firstOwner = coordinator.acquire(TimelineScope("backend", "first", "agent"))
        val secondOwner = coordinator.acquire(TimelineScope("backend", "second", "agent"))
        val first = openDesktopCanonicalPresentation(coordinator, firstOwner, backgroundScope)
        val second = openDesktopCanonicalPresentation(coordinator, secondOwner, backgroundScope, "missing")
        assertEquals("missing", second.missingTarget)
        first.close()
        assertTrue(coordinator.retire(firstOwner))
        assertFalse(coordinator.retire(secondOwner))
        second.close()
        assertTrue(coordinator.retire(secondOwner))
    }
}
