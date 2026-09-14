package com.letta.mobile.avatar.core

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * GazeDirector coverage against the bench policy in `RiveDesktopSpike.kt`:
 * plan selection by state, habituation, eye-lead vs head spring, own-thought
 * life without a pointer, and tick idempotence.
 */
class GazeDirectorTest {

    /** nextFloat() is 0 so weighted pick always takes the first plan row and dwell/gap sit at min. */
    private fun firstPick() = GazeDirector(ZeroRandom())

    @Test
    fun planSelectionPicksTheFirstWeightedLookForEachState() {
        val cases = listOf(
            AvatarState.IDLE to GazeTarget.OWN,
            AvatarState.LISTENING to GazeTarget.USER, // INPUT is skipped until the host supplies a rect
            AvatarState.THINKING to GazeTarget.OWN,
            AvatarState.SPEAKING to GazeTarget.USER,
            AvatarState.WAITING_INPUT to GazeTarget.USER,
            AvatarState.DRAGGED to GazeTarget.OWN, // CURSOR remaps away with no pointer
            AvatarState.SUCCESS to GazeTarget.USER,
            AvatarState.ERROR to GazeTarget.OWN,
            AvatarState.SLEEPING to GazeTarget.OWN,
            AvatarState.LOADING to GazeTarget.OWN,
        )
        for ((state, expected) in cases) {
            val g = firstPick()
            val pose = g.tick(0.016f, state)
            assertEquals(expected, pose.target, "state $state")
            assertEquals(expected, g.target, "state $state director target")
        }
    }

    @Test
    fun draggedLooksAtTheCursorWhenAPointerIsPresent() {
        val g = firstPick()
        val pose = g.tick(0.016f, AvatarState.DRAGGED, GazeWorld(pointer = GazePoint(0.4f, -0.2f)))
        assertEquals(GazeTarget.CURSOR, pose.target)
    }

    @Test
    fun listeningPicksInputOnlyWhenTheHostSuppliesARect() {
        val withInput = firstPick().tick(
            0.016f,
            AvatarState.LISTENING,
            GazeWorld(input = GazePoint(0f, 0.8f)),
        )
        assertEquals(GazeTarget.INPUT, withInput.target)
        val without = firstPick().tick(0.016f, AvatarState.LISTENING)
        assertEquals(GazeTarget.USER, without.target)
    }

    @Test
    fun habituationReducesInterestAndScoreOfTheAttendedTarget() {
        val g = GazeDirector(Random(1))
        val cursorLook = GazePlan.forState(AvatarState.IDLE).first { it.target == GazeTarget.CURSOR }
        val startScore = g.score(cursorLook)
        val startInterest = g.interestOf(GazeTarget.CURSOR)
        repeat(90) { i ->
            g.tick(0.016f, AvatarState.IDLE, GazeWorld(pointer = GazePoint(0.1f, 0.05f + i * 0.0002f)))
        }
        assertTrue(
            g.interestOf(GazeTarget.CURSOR) < startInterest - 0.2f,
            "cursor interest ${g.interestOf(GazeTarget.CURSOR)} should decay while attended",
        )
        assertTrue(
            g.score(cursorLook) < startScore,
            "habituated cursor score ${g.score(cursorLook)} should be below $startScore",
        )
        assertEquals(1f, g.interestOf(GazeTarget.OWN), "OWN never decays")
    }

    @Test
    fun habituationMakesAFreshTargetMoreLikelyThanTheOneJustStaredAt() {
        val g = GazeDirector(Random(1))
        repeat(120) { i ->
            g.tick(0.016f, AvatarState.IDLE, GazeWorld(pointer = GazePoint(0.05f, 0.05f + i * 0.0002f)))
        }
        val cursor = GazePlan.forState(AvatarState.IDLE).first { it.target == GazeTarget.CURSOR }
        val user = GazePlan.forState(AvatarState.IDLE).first { it.target == GazeTarget.USER }
        // Equal authored weights (25); after staring at the cursor the user look must win the score.
        assertEquals(cursor.weight, user.weight)
        assertTrue(
            g.score(user) > g.score(cursor),
            "user ${g.score(user)} should outrank habituated cursor ${g.score(cursor)}",
        )
    }

    @Test
    fun headLagsEyesTowardACursorTarget() {
        val g = GazeDirector(Random(1))
        val world = GazeWorld(pointer = GazePoint(1f, 0f), mode = GazeDriveMode.CURSOR)
        // Spike: a >0.15 base change resets the 350 ms lead; head stays put until then.
        var duringLead: GazePose = GazePose.CENTER
        repeat(12) { duringLead = g.tick(0.016f, AvatarState.IDLE, world) } // ~192 ms
        assertTrue(duringLead.lookX > 0.05f, "eyes ease toward the cursor during the lead: ${duringLead.lookX}")
        assertTrue(abs(duringLead.headX) < 0.05f, "head has not committed yet: ${duringLead.headX}")
        var afterLead: GazePose = duringLead
        repeat(20) { afterLead = g.tick(0.016f, AvatarState.IDLE, world) } // past 350 ms
        assertTrue(afterLead.headX > 0.05f, "head follows after the lead: ${afterLead.headX}")
        assertTrue(afterLead.lookX > 0f, "eyes are still on the cursor")
    }

    @Test
    fun nullPointerStillProducesOwnOrMutualGazeLife() {
        val g = GazeDirector(Random(7))
        val targets = mutableSetOf<GazeTarget>()
        var sawOffCentre = false
        var last: GazePose = GazePose.CENTER
        repeat(400) { // ~6.4 s at 16 ms — longer than the shortest idle dwell
            last = g.tick(0.016f, AvatarState.IDLE, GazeWorld())
            targets += last.target
            if (abs(last.lookX) > 0.01f || abs(last.lookY) > 0.01f) sawOffCentre = true
        }
        assertTrue(GazeTarget.OWN in targets, "own-thought looks: $targets")
        assertTrue(
            targets.size >= 2 || GazeTarget.USER in targets,
            "justified plan should not freeze on one dead look: $targets",
        )
        assertTrue(
            sawOffCentre || GazeTarget.USER in targets,
            "without a pointer the plan/saccades still move the eyes: $targets pose=$last",
        )
        assertTrue(last.lookTarget.x in 0f..1f && last.lookTarget.y in 0f..1f)
    }

    @Test
    fun tickIdempotenceNonPositiveDeltasDoNotAdvance() {
        val g = GazeDirector(Random(3))
        val world = GazeWorld(pointer = GazePoint(0.3f, -0.2f))
        val a = g.tick(0.016f, AvatarState.SPEAKING, world)
        val b = g.tick(0f, AvatarState.SPEAKING, world)
        val c = g.tick(-1f, AvatarState.SPEAKING, world)
        val d = g.tick(Float.NaN, AvatarState.SPEAKING, world)
        assertEquals(a.lookX, b.lookX)
        assertEquals(a.lookY, b.lookY)
        assertEquals(a.headX, b.headX)
        assertEquals(a.target, b.target)
        assertEquals(a, c)
        assertEquals(a.lookX, d.lookX)
        assertEquals(a.headY, d.headY)
        val e = g.tick(0.016f, AvatarState.SPEAKING, world)
        assertNotEquals(a.lookX, e.lookX, "a real tick after a no-op should still advance the ease")
    }

    @Test
    fun everyBehaviorStateHasAPlan() {
        val behavior = listOf(
            AvatarState.IDLE, AvatarState.LISTENING, AvatarState.DRAGGED,
            AvatarState.THINKING, AvatarState.WAITING_INPUT, AvatarState.SPEAKING,
            AvatarState.SUCCESS, AvatarState.ERROR, AvatarState.SLEEPING,
        )
        for (state in behavior) {
            val plan = GazePlan.forState(state)
            assertTrue(plan.isNotEmpty(), "$state")
            assertTrue(plan.sumOf { it.weight } > 0, "$state weights")
        }
    }

    @Test
    fun softPointerLookSaturatesBelowTheRim() {
        val bounds = GazeRect(0f, 0f, 100f, 100f)
        val far = GazeMath.pointerToGaze(10_000f, 50f, bounds, minReachPx = 360f)
        assertTrue(far.x < GazeMath.GAZE_MAX + 1e-4f)
        assertTrue(far.x > 0.8f)
        val center = GazeMath.pointerToGaze(50f, 50f, bounds, minReachPx = 360f)
        assertEquals(0f, center.x)
        assertEquals(0f, center.y)
    }

    @Test
    fun fromWindowMapsHostRectsAndLeavesMissingOnesUnavailable() {
        val mascot = GazeRect(0f, 0f, 80f, 80f)
        val empty = GazeWorld.fromWindow(GazeWindow(mascot = mascot, minReachPx = 360f))
        assertEquals(null, empty.pointer)
        assertEquals(null, empty.input)
        assertEquals(null, empty.timeline)
        assertEquals(null, GazeMath.rectCenterToGaze(null, mascot, 360f))
        assertEquals(null, GazeMath.rectCenterToGaze(GazeRect(0f, 0f, 0f, 10f), mascot, 360f))

        val with = GazeWorld.fromWindow(
            GazeWindow(
                mascot = mascot,
                minReachPx = 360f,
                pointerPx = GazePoint(200f, 40f),
                rects = GazeTargetRects(
                    input = GazeRect(0f, 400f, 200f, 440f),
                    timeline = GazeRect(300f, 0f, 500f, 200f),
                ),
            ),
        )
        val pointer = with.pointer
        val input = with.input
        val timeline = with.timeline
        assertTrue(pointer != null && pointer.x > 0f, "pointer right of tile: $pointer")
        assertTrue(input != null && input.y > 0f, "input below tile: $input")
        assertTrue(timeline != null && timeline.x > 0f, "timeline right of tile: $timeline")

        val listening = firstPick().tick(
            0.016f,
            AvatarState.LISTENING,
            GazeWorld.fromWindow(
                GazeWindow(
                    mascot = mascot,
                    minReachPx = 360f,
                    rects = GazeTargetRects(input = GazeRect(0f, 400f, 200f, 440f)),
                ),
            ),
        )
        assertEquals(GazeTarget.INPUT, listening.target)
    }

    private class ZeroRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextFloat(): Float = 0f
        override fun nextInt(until: Int): Int = 0
    }
}
