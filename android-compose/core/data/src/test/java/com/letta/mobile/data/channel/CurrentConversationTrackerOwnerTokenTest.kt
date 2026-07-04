package com.letta.mobile.data.channel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * letta-mobile-x1xnl: owner-token semantics that make a superseded chat VM
 * (a nav/dual-composition ghost) go inert so its stale message list can't
 * render as a stranded duplicate.
 */
class CurrentConversationTrackerOwnerTokenTest {
    @Test
    fun `claimOwner mints unique increasing tokens`() {
        val t = CurrentConversationTracker()
        val a = t.claimOwner()
        val b = t.claimOwner()
        assertNotEquals(a, b)
        assertTrue(b > a)
    }

    @Test
    fun `only the latest claimant is the current owner`() {
        val t = CurrentConversationTracker()
        val first = t.claimOwner()
        assertTrue(t.isCurrentOwner(first))

        val second = t.claimOwner()
        // The first owner is now superseded; the second is current.
        assertFalse("superseded owner must not be current", t.isCurrentOwner(first))
        assertTrue(t.isCurrentOwner(second))
    }

    @Test
    fun `token zero is never the current owner`() {
        val t = CurrentConversationTracker()
        t.claimOwner()
        assertFalse(t.isCurrentOwner(0L))
    }
}
