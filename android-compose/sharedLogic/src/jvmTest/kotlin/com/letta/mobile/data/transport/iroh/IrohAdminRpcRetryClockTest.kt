package com.letta.mobile.data.transport.iroh

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IrohAdminRpcRetryClockTest {
    @Test
    fun retryEvidenceUsesElapsedTimeInsteadOfWallTime() {
        var elapsed = 0L
        var wall = 1_000_000L
        val state = IrohAdminRpcExecutor.GenerationRetryState(1L) { elapsed }
        state.beginAdminRpc()
        state.recordIsolatedFailure()
        wall -= 2_000_000L
        assertTrue(wall < 0)
        assertEquals(1, state.youngInFlightAdminRpcCount(100L))
        assertEquals(0L, state.millisSinceLastStream())
        elapsed = 100L
        wall += 10_000_000L
        assertTrue(wall > 0)
        assertEquals(0, state.youngInFlightAdminRpcCount(100L))
        assertEquals(100L, state.millisSinceLastStream())
        assertEquals(100L, state.pathEvidence(100L, 100L).oldestInFlightAgeMs)
        assertEquals(1, state.pathEvidence(100L, 100L).recentRequestTimeouts)
        elapsed++
        assertEquals(0, state.pathEvidence(100L, 100L).recentRequestTimeouts)
        state.recordProofOfLife()
        assertEquals(0L, state.millisSinceLastStream())
    }

    @Test
    fun retryEscalationRequiresFailureAndElapsedIdleThresholds() = runTest {
        var elapsed = 0L
        val state = IrohAdminRpcExecutor.GenerationRetryState(1L) { elapsed }
        fun metrics() = IrohAdminRpcExecutor.RetryMetrics(state.consecutiveFailures, state.millisSinceLastStream())

        state.recordFailure()
        state.recordFailure()
        elapsed = 30_001L
        assertFalse(metrics().shouldEscalate)
        state.recordFailure()
        elapsed = 30_000L
        assertFalse(metrics().shouldEscalate)
        elapsed++
        assertTrue(metrics().shouldEscalate)
        state.recordProofOfLife()
        assertFalse(metrics().shouldEscalate)
    }
}
