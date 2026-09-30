package com.letta.mobile.desktop.input

import kotlin.test.Test
import kotlin.test.assertEquals

class FingerFlingVelocityTest {

    @Test
    fun aSteadyUpwardDragGivesNegativeVelocity() {
        val velocity = FingerFlingVelocity()
        var y = 100
        var time = 0L
        repeat(5) {
            velocity.record(y, time)
            y -= 10
            time += 8
        }
        assertEquals(-1.25f, velocity.liftVelocityY(time), 0.001f)
    }

    @Test
    fun aLiftAfterThePauseWindowGivesZero() {
        val velocity = FingerFlingVelocity()
        velocity.record(100, 0L)
        velocity.record(60, 32L)
        assertEquals(0f, velocity.liftVelocityY(32L + FingerFlingVelocity.FINGER_STOPPED_MILLIS + 1))
    }

    @Test
    fun onlyTheLast100msCount() {
        val velocity = FingerFlingVelocity()
        velocity.record(0, 0L)
        velocity.record(100, 10L)
        velocity.record(100, 200L)
        velocity.record(110, 280L)
        assertEquals(0.125f, velocity.liftVelocityY(280L), 0.001f)
    }

    @Test
    fun sameTimestampSamplesDoNotDivideByZero() {
        val velocity = FingerFlingVelocity()
        velocity.record(0, 5L)
        velocity.record(50, 5L)
        assertEquals(0f, velocity.liftVelocityY(5L))
    }

    @Test
    fun velocityIsClampedToEightPxPerMs() {
        val velocity = FingerFlingVelocity()
        velocity.record(0, 0L)
        velocity.record(1_000, 10L)
        assertEquals(8f, velocity.liftVelocityY(10L))
    }

    @Test
    fun resetClearsHistory() {
        val velocity = FingerFlingVelocity()
        velocity.record(0, 0L)
        velocity.record(40, 16L)
        velocity.reset()
        assertEquals(0f, velocity.liftVelocityY(16L))
    }
}
