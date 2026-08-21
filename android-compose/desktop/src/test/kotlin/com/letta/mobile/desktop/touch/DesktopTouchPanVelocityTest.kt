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
 * tests drive the realistic pattern (a dominant axis with opposite-signed minor
 * axis samples between its own) rather than a clean single-axis stream.
 */
class DesktopTouchPanVelocityTest {

    /**
     * Replays [samples] pans' worth of movement at a realistic ~10ms cadence:
     * [majorPixels] on the [horizontal] axis, and [minorPixels] on the other
     * one in the same instant, the way Windows interleaves them.
     */
    private fun pan(
        majorPixels: Float,
        horizontal: Boolean = false,
        minorPixels: Float = 0f,
        samples: Int = 10,
        startMillis: Long = 0L,
    ): DesktopTouchPanVelocity {
        val velocity = DesktopTouchPanVelocity()
        velocity.reset(startMillis)
        var now = startMillis
        repeat(samples) {
            now += SAMPLE_INTERVAL_MILLIS
            velocity.record(TouchPanSample(majorPixels, now, horizontal))
            if (minorPixels != 0f) {
                velocity.record(TouchPanSample(minorPixels, now, !horizontal))
            }
        }
        return velocity
    }

    @Test
    fun `downward swipe coasts downward and not sideways`() {
        val dominant = pan(majorPixels = 10f).dominant()

        assertTrue(dominant.dy > 0f, "expected a downward coast, got ${dominant.dy}")
        assertEquals(0f, dominant.dx, "a vertical pan must not coast sideways")
    }

    @Test
    fun `upward swipe coasts upward`() {
        assertTrue(pan(majorPixels = -10f).dominant().dy < 0f, "an upward pan must coast upward")
    }

    /**
     * The bug this class exists for: with one shared running average, the
     * opposite-signed horizontal jitter Windows interleaves into a vertical pan
     * dragged that average past zero, and the fling kicked backwards on release.
     */
    @Test
    fun `interleaved opposite-signed horizontal jitter cannot reverse the vertical coast`() {
        val dominant = pan(majorPixels = -8f, minorPixels = 3f, samples = 12).dominant()

        assertTrue(dominant.dy < 0f, "vertical coast reversed by horizontal jitter: ${dominant.dy}")
        assertEquals(0f, dominant.dx, "the minor axis must not be coasted on")
    }

    @Test
    fun `a genuinely horizontal pan coasts horizontally`() {
        val dominant = pan(majorPixels = 9f, horizontal = true, minorPixels = -1f, samples = 12).dominant()

        assertTrue(dominant.dx > 0f, "expected a horizontal coast, got ${dominant.dx}")
        assertEquals(0f, dominant.dy, "the minor axis must not be coasted on")
    }

    @Test
    fun `velocity is clamped to the sane maximum`() {
        // 5000px between samples is sensor noise, not a gesture.
        val dominant = pan(majorPixels = 5_000f, samples = 20).dominant()

        assertTrue(
            abs(dominant.dy) <= MAX_PAN_VELOCITY_PX_PER_MS,
            "velocity escaped the clamp: ${dominant.dy}",
        )
    }

    @Test
    fun `reset clears a previous gesture so it cannot seed the next coast`() {
        val velocity = pan(majorPixels = 20f)

        velocity.reset(1_000L)

        val dominant = velocity.dominant()
        assertEquals(0f, dominant.dx)
        assertEquals(0f, dominant.dy)
    }

    @Test
    fun `a stationary hold leaves no coast`() {
        assertEquals(0f, pan(majorPixels = 0f).dominant().dy)
    }

    private companion object {
        /** The cadence Windows delivers touch pan updates at. */
        const val SAMPLE_INTERVAL_MILLIS = 10L
    }
}
