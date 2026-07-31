package com.letta.mobile.data.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-lgns8.22.5: unit cover for the extracted approval-gate seam.
 *
 * These assert the two properties the engine relies on and that were previously
 * only observable through a full turn: PER-RUNTIME ISOLATION (letta-mobile-8xxzv
 * / vilsn.6 — A's parked question must not pause or be cleared by B) and
 * NOT-CONSUME-ON-READ (letta-mobile-vilsn — a failed `client.input` must leave
 * the id answerable).
 */
class ApprovalRegistryTest {

    private val keyA = TurnRuntimeKey("agent-1", "conv-a")
    private val keyB = TurnRuntimeKey("agent-1", "conv-b")

    @Test
    fun recordMakesGateOutstandingForThatRuntimeOnly() {
        val registry = ApprovalRegistry()
        registry.record(keyA, ApprovalRegistry.Gate("call_1", "perm-call_1"))

        assertTrue(registry.hasOutstanding(keyA))
        assertFalse(registry.hasOutstanding(keyB))
        assertEquals(mapOf("call_1" to "perm-call_1"), registry.outstanding(keyA))
        assertEquals(emptyMap(), registry.outstanding(keyB))
    }

    @Test
    fun clearKeyDoesNotTouchASiblingRuntime() {
        val registry = ApprovalRegistry()
        registry.record(keyA, ApprovalRegistry.Gate("call_a", "perm-call_a"))
        registry.record(keyB, ApprovalRegistry.Gate("call_b", "perm-call_b"))

        // Conversation B reaching a terminal must not free A's parked question.
        registry.clearKey(keyB)

        assertTrue(registry.hasOutstanding(keyA))
        assertFalse(registry.hasOutstanding(keyB))
        assertEquals("perm-call_a", registry.approvalIdFor("call_a"))
        assertNull(registry.approvalIdFor("call_b"))
    }

    @Test
    fun resolveClearsOnlyTheMatchingGate() {
        val registry = ApprovalRegistry()
        registry.record(keyA, ApprovalRegistry.Gate("call_1", "perm-call_1"))
        registry.record(keyA, ApprovalRegistry.Gate("call_2", "perm-call_2"))

        registry.resolve(keyA, "call_1")

        assertTrue(registry.hasOutstanding(keyA))
        assertEquals(mapOf("call_2" to "perm-call_2"), registry.outstanding(keyA))
    }

    @Test
    fun resolveIsScopedToItsRuntimeKey() {
        val registry = ApprovalRegistry()
        registry.record(keyA, ApprovalRegistry.Gate("call_1", "perm-call_1"))

        registry.resolve(keyB, "call_1")

        assertTrue(registry.hasOutstanding(keyA))
    }

    @Test
    fun approvalIdForFindsTheGateWithoutConsumingIt() {
        val registry = ApprovalRegistry()
        registry.record(keyA, ApprovalRegistry.Gate("call_1", "perm-call_1"))

        assertEquals("perm-call_1", registry.approvalIdFor("call_1"))
        // A failed send must leave the id answerable for the user's retry.
        assertEquals("perm-call_1", registry.approvalIdFor("call_1"))
        assertTrue(registry.hasOutstanding(keyA))
    }

    @Test
    fun approvalIdForSearchesEveryRuntimeKey() {
        val registry = ApprovalRegistry()
        registry.record(keyB, ApprovalRegistry.Gate("call_b", "perm-call_b"))

        assertEquals("perm-call_b", registry.approvalIdFor("call_b"))
        assertNull(registry.approvalIdFor("call_unknown"))
    }

    @Test
    fun clearIfMatchesConsumesTheGateAfterASuccessfulSend() {
        val registry = ApprovalRegistry()
        registry.record(keyA, ApprovalRegistry.Gate("call_1", "perm-call_1"))

        registry.clearIfMatches(ApprovalRegistry.Gate("call_1", "perm-call_1"))

        assertFalse(registry.hasOutstanding(keyA))
        assertNull(registry.approvalIdFor("call_1"))
        assertEquals(0, registry.trackedCount())
    }

    @Test
    fun clearIfMatchesLeavesAReSurfacedGateAlone() {
        val registry = ApprovalRegistry()
        registry.record(keyA, ApprovalRegistry.Gate("call_1", "perm-call_1"))
        // Same tool call parked again under a NEW control-request id.
        registry.record(keyA, ApprovalRegistry.Gate("call_1", "perm-call_1-retry"))

        // The late success for the OLD id must not delete the live gate.
        registry.clearIfMatches(ApprovalRegistry.Gate("call_1", "perm-call_1"))

        assertEquals("perm-call_1-retry", registry.approvalIdFor("call_1"))
        assertTrue(registry.hasOutstanding(keyA))
    }

    @Test
    fun emptiedRuntimeKeysAreDroppedSoAnIdleClientTracksNothing() {
        val registry = ApprovalRegistry()
        registry.record(keyA, ApprovalRegistry.Gate("call_1", "perm-call_1"))
        registry.record(keyB, ApprovalRegistry.Gate("call_2", "perm-call_2"))
        assertEquals(2, registry.trackedCount())

        registry.resolve(keyA, "call_1")
        registry.clearKey(keyB)

        assertEquals(0, registry.trackedCount())
    }

    @Test
    fun overflowEvictsTheLeastRecentRuntimeAndNeverTheOneBeingRecorded() {
        val registry = ApprovalRegistry(cap = 2)
        registry.record(TurnRuntimeKey("a", "1"), ApprovalRegistry.Gate("call_1", "perm-1"))
        registry.record(TurnRuntimeKey("a", "2"), ApprovalRegistry.Gate("call_2", "perm-2"))
        registry.record(TurnRuntimeKey("a", "3"), ApprovalRegistry.Gate("call_3", "perm-3"))

        assertEquals(2, registry.trackedCount())
        assertNull(registry.approvalIdFor("call_1"))
        assertEquals("perm-2", registry.approvalIdFor("call_2"))
        assertEquals("perm-3", registry.approvalIdFor("call_3"))
    }
}
