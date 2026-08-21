package com.letta.mobile.desktop.touch

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for the coast a Windows touch pan hands to
 * [DesktopTouchFling].
 *
 * Windows pans a touchscreen itself and the JDK reports it as wheel events
 * carrying a touch phase, interleaving both axes within one gesture — so these
 * tests feed the realistic pattern (a dominant axis with opposite-signed minor
 * axis samples between its own) rather than a clean single-axis stream.
 */
class DesktopTouchPanVelocityTest {

    @Test
    fun `vertical velocity keeps the sign of the swipe`() {
        val velocity = DesktopTouchPanVelocity()
        velocity.reset(0L)
        repeat(10) { step -> velocity.record(pixels = 10f, atMillis = 10L * (step + 1), horizontal = false) }

        val dominant = velocity.dominant()
        assertTrue(dominant.dy > 0f, "expected a downward coast, got ${dominant.dy}")
        assertEquals(0f, dominant.dx, "a vertical pan must not coast sideways")
    }

    @Test
    fun `upward swipe coasts upward`() {
        val velocity = DesktopTouchPanVelocity()
        velocity.reset(0L)
        repeat(10) { step -> velocity.record(pixels = -10f, atMillis = 10L * (step + 1), horizontal = false) }

        assertTrue(velocity.dominant().dy < 0f, "an upward pan must coast upward")
    }

    /**
     * The bug this class exists for: with one shared running average, the
     * opposite-signed horizontal jitter that Windows interleaves into a
     * vertical pan dragged the average past zero and the fling kicked
     * backwards on release.
     */
    @Test
    fun `interleaved opposite-signed horizontal jitter cannot reverse the vertical coast`() {
        val velocity = DesktopTouchPanVelocity()
        velocity.reset(0L)
        var now = 0L
        repeat(12) {
            now += 10
            velocity.record(pixels = -8f, atMillis = now, horizontal = false)
            velocity.record(pixels = 3f, atMillis = now, horizontal = true)
        }

        val dominant = velocity.dominant()
        assertTrue(dominant.dy < 0f, "vertical coast reversed by horizontal jitter: ${dominant.dy}")
        assertEquals(0f, dominant.dx, "the minor axis must not be coasted on")
    }

    @Test
    fun `a genuinely horizontal pan coasts horizontally`() {
        val velocity = DesktopTouchPanVelocity()
        velocity.reset(0L)
        var now = 0L
        repeat(12) {
            now += 10
            velocity.record(pixels = 9f, atMillis = now, horizontal = true)
            velocity.record(pixels = -1f, atMillis = now, horizontal = false)
        }

        val dominant = velocity.dominant()
        assertTrue(dominant.dx > 0f, "expected a horizontal coast, got ${dominant.dx}")
        assertEquals(0f, dominant.dy, "the minor axis must not be coasted on")
    }

    @Test
    fun `velocity is clamped to the sane maximum`() {
        val velocity = DesktopTouchPanVelocity()
        velocity.reset(0L)
        repeat(20) { step ->
            // 5000px in 1ms is sensor noise, not a gesture.
            velocity.record(pixels = 5000f, atMillis = step + 1L, horizontal = false)
        }

        assertTrue(
            abs(velocity.dominant().dy) <= MAX_PAN_VELOCITY_PX_PER_MS,
            "velocity escaped the clamp: ${velocity.dominant().dy}",
        )
    }

    @Test
    fun `reset clears a previous gesture so it cannot seed the next coast`() {
        val velocity = DesktopTouchPanVelocity()
        velocity.reset(0L)
        repeat(10) { step -> velocity.record(pixels = 20f, atMillis = 10L * (step + 1), horizontal = false) }

        velocity.reset(1_000L)

        val dominant = velocity.dominant()
        assertEquals(0f, dominant.dx)
        assertEquals(0f, dominant.dy)
    }

    @Test
    fun `a stationary hold leaves no coast`() {
        val velocity = DesktopTouchPanVelocity()
        velocity.reset(0L)
        repeat(10) { step -> velocity.record(pixels = 0f, atMillis = 10L * (step + 1), horizontal = false) }

        assertEquals(0f, velocity.dominant().dy)
    }
}
