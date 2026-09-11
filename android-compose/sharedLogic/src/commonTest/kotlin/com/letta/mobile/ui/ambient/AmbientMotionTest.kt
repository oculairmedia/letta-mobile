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

    @Test
    fun idleNeverBrightensTheGlowOnTheWayOut() {
        // Idle is where every transient status ENDS, and the tint fade takes
        // longer than the envelope retarget. An Idle envelope above the value
        // it is fading FROM makes the glow brighten as it disappears — the
        // rebound the settle timing was supposed to remove.
        val idle = AmbientMotion.spec(AmbientMotionStatus.Idle)
        AmbientMotionStatus.entries.map { AmbientMotion.spec(it) }.filter { it.isTransient }.forEach { transient ->
            assertTrue(
                idle.settledEnvelope <= transient.settledEnvelope,
                "idle ${idle.settledEnvelope} must not exceed a transient settle of ${transient.settledEnvelope}",
            )
        }
    }

    @Test
    fun aTransientStatusIsHeldForItsWholeRampNotJustItsDecay() {
        // Both hosts hard-coded a 1400 ms hold, which cancelled Completed's
        // decay at 58% — Idle then animated the envelope back UP, so the
        // afterglow read as a rebound. The hold must come from the same table
        // as the ramp it waits on, rise included: a hold that ended mid-rise
        // would hand Idle a still-climbing envelope and rebound the same way.
        AmbientMotionStatus.entries.filter { AmbientMotion.spec(it).isTransient }.forEach { status ->
            val spec = AmbientMotion.spec(status)
            assertEquals(
                AmbientMotion.ramp(current = 0f, status = status).totalMillis,
                AmbientMotion.holdMillis(status),
                "$status must be held for its whole ramp",
            )
            assertTrue(
                AmbientMotion.holdMillis(status) > spec.settleMillis,
                "$status is held for its rise as well as its decay",
            )
        }
    }

    @Test
    fun aLandingStatusNeverStepsTheIntensityUp() {
        // The defect this table now forbids: a status used to snap to its bloom, so a
        // turn ending at the streaming envelope brightened by half between two frames.
        // Whatever the glow is at, reaching a higher bloom must take time.
        listOf(0f, 0.3f, 0.9f, 1f, 1.5f, 1.6f).forEach { current ->
            AmbientMotionStatus.entries.forEach { status ->
                val ramp = AmbientMotion.ramp(current = current, status = status)
                // Whichever move the host makes first, it takes time. Nothing is instant.
                val firstMoveMillis = if (ramp.risesFirst) ramp.riseMillis else ramp.settleMillis
                assertTrue(firstMoveMillis > 0, "$status from $current must not move instantly")
                if (AmbientMotion.spec(status).isTransient && ramp.bloomEnvelope > current) {
                    assertTrue(
                        ramp.risesFirst,
                        "$status from $current climbs to ${ramp.bloomEnvelope}, so it needs a rise",
                    )
                }
            }
        }
    }

    @Test
    fun aFinishedTurnClimbsIntoItsBloomFromTheStreamingGlow() {
        val streaming = AmbientMotion.spec(AmbientMotionStatus.Running).settledEnvelope
        val completed = AmbientMotion.spec(AmbientMotionStatus.Completed)
        val ramp = AmbientMotion.ramp(current = streaming, status = AmbientMotionStatus.Completed)
        assertTrue(ramp.risesFirst, "a turn ends brighter than it streamed, so it rises")
        assertEquals(AmbientMotion.BLOOM_RISE_MILLIS, ramp.riseMillis)
        assertEquals(completed.bloomEnvelope, ramp.bloomEnvelope)
        assertEquals(completed.settleMillis, ramp.settleMillis)
        assertEquals(completed.settledEnvelope, ramp.settledEnvelope)
    }

    @Test
    fun aGlowAlreadyAboveTheBloomDecaysFromWhereItIs() {
        // Re-entering a transient status mid-decay must not drop the glow to meet the
        // table either: a downward step is as visible as an upward one.
        val completed = AmbientMotion.spec(AmbientMotionStatus.Completed)
        val ramp = AmbientMotion.ramp(current = completed.bloomEnvelope + 0.1f, status = AmbientMotionStatus.Completed)
        assertEquals(0, ramp.riseMillis, "nothing to climb")
        assertEquals(completed.settledEnvelope, ramp.settledEnvelope)
    }

    @Test
    fun aContinuousStatusJustEasesToItsLevel() {
        listOf(AmbientMotionStatus.Idle, AmbientMotionStatus.Running, AmbientMotionStatus.Active)
            .forEach { status ->
                val ramp = AmbientMotion.ramp(current = 0f, status = status)
                val spec = AmbientMotion.spec(status)
                assertEquals(0, ramp.riseMillis, "$status has no bloom to climb")
                assertEquals(spec.settledEnvelope, ramp.bloomEnvelope, "$status names one level")
                assertEquals(spec.settledEnvelope, ramp.settledEnvelope)
                assertTrue(ramp.settleMillis > 0, "$status still eases rather than jumping")
                assertEquals(0, AmbientMotion.holdMillis(status), "$status is not held")
            }
    }
}
