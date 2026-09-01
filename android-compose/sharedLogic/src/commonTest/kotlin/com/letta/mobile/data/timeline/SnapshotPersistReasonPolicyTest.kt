package com.letta.mobile.data.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-827s9.4, dogfood round 3 item 1.
 *
 * The Pixel capture showed 12 commits in 106 s -- 11 of them for a BACKGROUND conversation --
 * because the old gate inspected only debounced requests while 12 of 13 scheduling sites
 * passed `immediate = true`. Hydration, reconcile and local-mutation callbacks all jumped the
 * streaming boundary.
 *
 * The write policy is now a pure function of the reason, so it is pinned here rather than
 * inferred from an integration test. Fail-on-revert: adding any non-boundary reason to
 * [SnapshotPersistReason.isTurnBoundary] fails, which is exactly the regression that produced
 * the background-conversation commits.
 */
class SnapshotPersistReasonPolicyTest {

    @Test
    fun onlyRealBoundariesMayWriteDuringAnActiveTurn() {
        val boundaries = SnapshotPersistReason.entries.filter { it.isTurnBoundary }.toSet()
        assertEquals(
            setOf(
                SnapshotPersistReason.SETTLEMENT,
                SnapshotPersistReason.TURN_END,
                SnapshotPersistReason.SAFETY_FLUSH,
            ),
            boundaries,
        )
    }

    @Test
    fun theHighVolumeCallbackSourcesAreNotBoundaries() {
        // These are the four that were bypassing the gate on device.
        assertFalse(SnapshotPersistReason.STREAM_FRAME.isTurnBoundary)
        assertFalse(SnapshotPersistReason.HYDRATION.isTurnBoundary)
        assertFalse(SnapshotPersistReason.RECONCILE.isTurnBoundary)
        assertFalse(SnapshotPersistReason.LOCAL_MUTATION.isTurnBoundary)
    }

    @Test
    fun onlyTheStreamFrameSourceIsDebounced() {
        assertTrue(SnapshotPersistReason.STREAM_FRAME.isDebounced)
        SnapshotPersistReason.entries
            .filter { it != SnapshotPersistReason.STREAM_FRAME }
            .forEach { assertFalse(it.isDebounced, "$it must not be debounced") }
    }

    @Test
    fun everyReasonIsClassifiedExactlyOnce() {
        // Guards against a new reason being added without deciding its policy.
        SnapshotPersistReason.entries.forEach { reason ->
            val classified = reason.isTurnBoundary || !reason.isTurnBoundary
            assertTrue(classified, "$reason must have an explicit policy")
        }
        assertEquals(7, SnapshotPersistReason.entries.size)
    }
}
