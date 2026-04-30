package com.letta.mobile.clientmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ClientModeSuppressionGate].
 *
 * Exercises the full mark / release / releaseAll lifecycle plus the
 * [com.letta.mobile.data.timeline.SubscriberSuppressionGate.isSuppressed]
 * read path that TimelineSyncLoop polls. See plan
 * 2026-04-clientmode-double-bubble-fix.md.
 */
class ClientModeSuppressionGateTest {

    @Test
    fun `isSuppressed returns false for unmarked conversation`() {
        val gate = ClientModeSuppressionGate()
        assertFalse(gate.isSuppressed("conv-1"))
    }

    @Test
    fun `markOwned then isSuppressed returns true`() {
        val gate = ClientModeSuppressionGate()
        gate.markOwned("conv-2")
        assertTrue(gate.isSuppressed("conv-2"))
        assertFalse("Other conversations must remain unaffected", gate.isSuppressed("conv-3"))
    }

    @Test
    fun `markOwned is idempotent`() {
        val gate = ClientModeSuppressionGate()
        gate.markOwned("conv-4")
        gate.markOwned("conv-4")
        gate.markOwned("conv-4")
        assertEquals(setOf("conv-4"), gate.snapshot())
        assertTrue(gate.isSuppressed("conv-4"))
    }

    @Test
    fun `release removes ownership and unsuppresses`() {
        val gate = ClientModeSuppressionGate()
        gate.markOwned("conv-5")
        gate.release("conv-5")
        assertFalse(gate.isSuppressed("conv-5"))
        assertEquals(emptySet<String>(), gate.snapshot())
    }

    @Test
    fun `release is idempotent for unknown conversations`() {
        val gate = ClientModeSuppressionGate()
        gate.release("never-marked")
        // No throw, no state change.
        assertEquals(emptySet<String>(), gate.snapshot())
    }

    @Test
    fun `releaseAll clears every owned conversation`() {
        val gate = ClientModeSuppressionGate()
        gate.markOwned("a")
        gate.markOwned("b")
        gate.markOwned("c")
        assertEquals(3, gate.snapshot().size)

        gate.releaseAll()

        assertEquals(emptySet<String>(), gate.snapshot())
        assertFalse(gate.isSuppressed("a"))
        assertFalse(gate.isSuppressed("b"))
        assertFalse(gate.isSuppressed("c"))
    }

    @Test
    fun `markOwned ignores blank conversation ids`() {
        val gate = ClientModeSuppressionGate()
        gate.markOwned("")
        gate.markOwned("   ")
        assertEquals(emptySet<String>(), gate.snapshot())
        assertFalse(gate.isSuppressed(""))
    }

    @Test
    fun `release ignores blank conversation ids`() {
        val gate = ClientModeSuppressionGate()
        gate.markOwned("real")
        gate.release("")
        gate.release("   ")
        assertTrue("Blank releases must not affect marked conversations", gate.isSuppressed("real"))
    }
}
