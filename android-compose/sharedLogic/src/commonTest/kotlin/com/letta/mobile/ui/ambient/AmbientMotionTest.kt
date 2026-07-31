package com.letta.mobile.ui.ambient

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AmbientMotionTest {

    @Test
    fun everyStatusHasPositiveMotionValues() {
        AmbientMotionStatus.entries.forEach { status ->
            val spec = AmbientMotion.spec(status)
            assertTrue(spec.speed > 0f, "$status speed")
            assertTrue(spec.agitation > 0f, "$status agitation")
            assertTrue(spec.bloomEnvelope > 0f, "$status bloom")
            assertTrue(spec.settledEnvelope > 0f, "$status settled")
        }
    }

    @Test
    fun transientStatesBloomThenDecay() {
        // The review's core complaint: Completed glowed at constant intensity
        // forever. Transient states must land brighter than they settle, and
        // must actually take time to settle.
        listOf(AmbientMotionStatus.Failed, AmbientMotionStatus.Completed).forEach { status ->
            val spec = AmbientMotion.spec(status)
            assertTrue(spec.isTransient, "$status must be transient")
            assertTrue(spec.bloomEnvelope > spec.settledEnvelope, "$status bloom > settled")
            assertTrue(spec.settleMillis > 0, "$status needs a settle duration")
        }
    }

    @Test
    fun continuousStatesHoldSteady() {
        listOf(AmbientMotionStatus.Idle, AmbientMotionStatus.Running, AmbientMotionStatus.Active)
            .forEach { status ->
                val spec = AmbientMotion.spec(status)
                assertEquals(false, spec.isTransient, "$status is continuous")
                assertEquals(0, spec.settleMillis, "$status has no settle phase")
            }
    }

    @Test
    fun motionDistinguishesTheStates() {
        // Status must drive behavior, not just tint: no two visible statuses
        // may share the same (speed, agitation) pair, or they collapse back
        // into "the same animation in different colors".
        val visible = AmbientMotionStatus.entries.filter { it != AmbientMotionStatus.Idle }
        val signatures = visible.map { AmbientMotion.spec(it) }.map { it.speed to it.agitation }
        assertEquals(signatures.distinct().size, signatures.size, "each status needs distinct motion")
    }

    @Test
    fun runningIsFasterAndNoisierThanIdle() {
        val idle = AmbientMotion.spec(AmbientMotionStatus.Idle)
        val running = AmbientMotion.spec(AmbientMotionStatus.Running)
        assertTrue(running.speed > idle.speed)
        assertTrue(running.agitation > idle.agitation)
    }

    @Test
    fun failedSettlesAboveCompletedAfterglow() {
        // Failure must stay MORE visible than a finished turn's afterglow —
        // it is the one state a user must not miss.
        val failed = AmbientMotion.spec(AmbientMotionStatus.Failed)
        val completed = AmbientMotion.spec(AmbientMotionStatus.Completed)
        assertTrue(failed.settledEnvelope > completed.settledEnvelope)
    }
}
