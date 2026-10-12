package com.letta.mobile.ui.screens.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** letta-mobile-dpv7m.3: a deep-linked pairing link is handed over once, in memory. */
class PendingPairLinkTest {
    private val link = "meridian://pair?qr=letta-qr-v1.eyJ2IjoxfQ&dial=h%3A1"

    @Test
    fun aPairLinkIsTakenExactlyOnce() {
        assertTrue(PendingPairLink.offer(link))
        assertEquals(link, PendingPairLink.take())
        assertNull(PendingPairLink.take())
    }

    @Test
    fun otherDeepLinksAreNotTreatedAsPairing() {
        assertFalse(PendingPairLink.offer("letta://agent/agent-1"))
        assertFalse(PendingPairLink.offer(null))
        assertNull(PendingPairLink.take())
    }
}
