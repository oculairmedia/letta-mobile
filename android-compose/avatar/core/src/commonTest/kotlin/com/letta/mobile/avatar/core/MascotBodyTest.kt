package com.letta.mobile.avatar.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/** letta-mobile-te3n1.3: the mascot's body springs - travel, squash/stretch, lean, entrance. */
class MascotBodyTest {
    private val tune = MascotBodyTune()
    private val maxScale = 1f + tune.maxStretch

    /** Steps [body] at 60 fps for up to [seconds], calling [each] after every step with the time. */
    private fun run(body: MascotBody, seconds: Float, each: (Float, Float) -> Unit = { _, _ -> }) {
        var t = 0f
        while (t < seconds) {
            val impact = body.step(FRAME_STEP)
            t += FRAME_SECONDS
            each(t, impact)
        }
    }

    @Test
    fun theAreaHoldsAndTheClampsHoldAtEveryStep() {
        val body = MascotBody()
        body.hop(MascotVector(600f, -200f))
        run(body, 1f) { _, _ ->
            assertEquals(1f, body.scaleX * body.scaleY, 0.01f, "area")
            assertTrue(body.scaleY <= maxScale + EPS && body.scaleX <= maxScale + EPS, "stretch <= 16 %")
            assertTrue(body.scaleY >= 0.86f - EPS && body.scaleX >= 0.86f - EPS, "squash <= 14 %")
            assertTrue(abs(body.rotationDegrees) <= tune.maxLeanDegrees + EPS, "lean <= 8 deg")
        }
        // A pile of impulses still cannot squash it past the clamp.
        repeat(5) { body.impulse(1f) }
        run(body, 0.2f) { _, _ -> assertTrue(body.scaleY >= 0.86f - EPS) }
    }

    @Test
    fun aSixHundredHopSettlesInTimeWithExactlyOneOvershoot() {
        val body = MascotBody()
        body.hop(MascotVector(600f, 0f))
        var overshoots = 0
        var counted = false
        var settledAt = -1f
        run(body, 1f) { t, _ ->
            val error = body.travel - 1f
            if (error > OVERSHOOT && !counted) {
                overshoots++
                counted = true
            }
            if (error < 0f) counted = false
            if (abs(error) > SETTLED) settledAt = -1f else if (settledAt < 0f) settledAt = t
        }
        assertEquals(1, overshoots, "one visible overshoot")
        assertTrue(settledAt in 0f..0.45f, "settled within 1 % by 450 ms (incl. the crouch), was $settledAt")
        assertTrue(body.atRest)
    }

    @Test
    fun aHopCrouchesBeforeItLeavesAndLandsOnce() {
        val body = MascotBody()
        body.hop(MascotVector(0f, -400f))
        body.step(seconds(tune.anticipationSeconds / 2f))
        assertEquals(0f, body.travel, "still on the ground while it crouches")
        assertTrue(body.scaleY < 1f, "crouched")
        var landings = 0
        var impact = 0f
        run(body, 1f) { _, landed ->
            if (landed > 0f) {
                landings++
                impact = landed
            }
        }
        assertEquals(1, landings)
        assertTrue(impact in 0.1f..1f, "landed with some impact: $impact")
    }

    @Test
    fun anImpulseDecaysToRestWithin400ms() {
        val body = MascotBody()
        body.impulse(1f)
        var restedAt = -1f
        run(body, 1f) { t, _ -> if (restedAt < 0f && body.atRest) restedAt = t }
        assertTrue(restedAt in 0f..0.4f, "rest at $restedAt")
        assertEquals(1f, body.scaleX)
        assertEquals(1f, body.scaleY)
    }

    @Test
    fun theSameStepsGiveTheSamePose() {
        val a = MascotBody()
        val b = MascotBody()
        a.hop(MascotVector(320f, -140f))
        b.hop(MascotVector(320f, -140f))
        repeat(40) { i ->
            val dt = if (i % 3 == 0) FRAME_STEP * 2 else FRAME_STEP
            assertEquals(a.step(dt), b.step(dt))
            assertEquals(a.travel, b.travel)
            assertEquals(a.scaleY, b.scaleY)
            assertEquals(a.rotationDegrees, b.rotationDegrees)
        }
    }

    @Test
    fun aHopMidFlightCarriesOnWithoutACrouchOrAJump() {
        val body = MascotBody()
        body.hop(MascotVector(500f, 0f))
        run(body, 0.15f)
        val lean = body.rotationDegrees
        body.hop(MascotVector(-300f, 0f))
        assertEquals(0f, body.travel)
        body.step(FRAME_STEP)
        assertTrue(body.travel > 0f, "no crouch mid-flight")
        assertTrue(abs(body.rotationDegrees - lean) < tune.maxLeanDegrees, "the lean carries on")
    }

    @Test
    fun snapStandsItStillAtOnce() {
        val body = MascotBody()
        body.hop(MascotVector(600f, 0f))
        run(body, 0.1f)
        body.snap()
        assertTrue(body.atRest)
        assertEquals(1f, body.travel)
        assertEquals(1f, body.scaleY)
        assertEquals(0f, body.rotationDegrees)
        assertEquals(0f, body.step(FRAME_STEP))
    }

    @Test
    fun movingRightLeansRightAndReportsMotion() {
        val body = MascotBody()
        body.hop(MascotVector(600f, 0f))
        run(body, tune.anticipationSeconds + 0.08f)
        assertTrue(body.rotationDegrees > 0f)
        assertTrue(body.motionX > 0f)
        assertEquals(0f, body.motionY)
        assertTrue(body.scaleX > 1f, "stretched along its travel")
    }

    @Test
    fun aDragIsHostMotionTheBodyLeansIntoAndReports() {
        val body = MascotBody()
        body.drag(MascotVector(tune.stretchSpeedDp / 2f, 0f))
        assertEquals(0.5f, body.motionX, EPS)
        assertTrue(!body.atRest, "a drag keeps it moving")
        run(body, 0.2f)
        assertTrue(body.rotationDegrees > 0f, "leans into the drag")
        body.drag(null)
        run(body, 0.6f)
        assertTrue(body.atRest)
        assertEquals(0f, body.motionX)
    }

    @Test
    fun theEntranceRisesWithOneOvershootAndIsContinuous() {
        assertEquals(0f, MascotEntrance.scale(0f))
        assertEquals(1f, MascotEntrance.scale(1f), EPS)
        assertEquals(0f, MascotEntrance.deform(1f), EPS)
        var peaks = 0
        var previous = MascotEntrance.scale(STEP)
        var rising = true
        var t = STEP * 2
        while (t <= 1f) {
            val scale = MascotEntrance.scale(t)
            assertTrue(abs(scale - previous) < 0.05f, "no jump at $t")
            if (rising && scale < previous) {
                peaks++
                rising = false
            }
            if (!rising && scale > previous) rising = true
            previous = scale
            t += STEP
        }
        assertEquals(1, peaks, "one overshoot")
    }

    private companion object {
        const val FRAME_SECONDS = 1f / 60f
        val FRAME_STEP = seconds(FRAME_SECONDS)

        fun seconds(value: Float): Duration = (value * 1_000_000_000f).toLong().nanoseconds
        const val STEP = 0.01f
        const val EPS = 0.001f
        const val OVERSHOOT = 0.01f
        const val SETTLED = 0.01f
    }
}
