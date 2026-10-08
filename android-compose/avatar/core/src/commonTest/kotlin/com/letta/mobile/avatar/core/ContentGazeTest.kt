package com.letta.mobile.avatar.core

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mascot looks toward the content it is writing (letta-mobile-bglj6.1 device feedback): up and
 * to the right of the companion while at work, never off to the left, wandering biased right / up
 * at rest, and holding still under reduced motion.
 */
class ContentGazeTest {

    /** The phone layout from the report: the companion at the bottom-left beside the prompt. */
    private val companion = GazeRect(left = 40f, top = 1240f, right = 200f, bottom = 1400f)
    private val timeline = GazeRect(left = 0f, top = 200f, right = 1280f, bottom = 1360f)
    private val input = GazeRect(left = 230f, top = 1420f, right = 1100f, bottom = 1520f)
    private val reachPx = 1260f

    private companion object {
        val AWAY_REACH = 0.45f..0.85f
    }

    private fun phoneWorld(reducedMotion: Boolean = false) = GazeWorld.fromWindow(
        GazeWindow(
            mascot = companion,
            reach = GazeReach(reachPx),
            rects = GazeTargetRects(input = input, timeline = timeline),
        ),
        reducedMotion = reducedMotion,
    )

    // --- the pure look ------------------------------------------------------------------------

    @Test
    fun workingLooksUpAndRightWhenTheContentIsAboveRight() {
        val edge = ContentGaze.liveEdgeOf(timeline)
        for (state in listOf(AvatarState.THINKING, AvatarState.WORKING, AvatarState.SPEAKING)) {
            val look = assertNotNull(ContentGaze.look(companion, edge, state, reachPx), "$state")
            assertTrue(look.x > 0.1f, "$state looks right toward the reply: $look")
            assertTrue(look.y < 0f, "$state looks up toward the reply: $look")
        }
    }

    @Test
    fun theLiveEdgeIsTheNewestBandOfTheReversedList() {
        val edge = ContentGaze.liveEdgeOf(timeline)
        assertEquals(timeline.centerX, edge.x)
        assertTrue(edge.y < timeline.bottom && edge.y > timeline.centerY, "near the bottom (newest) edge: $edge")
    }

    @Test
    fun restingStatesLeaveTheLookToThePlan() {
        val edge = ContentGaze.liveEdgeOf(timeline)
        for (state in listOf(AvatarState.IDLE, AvatarState.LISTENING, AvatarState.WAITING_INPUT)) {
            assertNull(ContentGaze.look(companion, edge, state, reachPx), "$state")
        }
    }

    @Test
    fun contentToTheLeftNeverPullsTheEyesPastTheSmallAngle() {
        val farLeft = GazePoint(-4000f, 1300f)
        val look = assertNotNull(ContentGaze.look(companion, farLeft, AvatarState.THINKING, reachPx))
        assertEquals(ContentGaze.MAX_LEFT_X, look.x)
        assertEquals(ContentGaze.MAX_LEFT_X, ContentGaze.constrain(GazePoint(-0.9f, 0.3f)).x)
        assertEquals(0.3f, ContentGaze.constrain(GazePoint(-0.9f, 0.3f)).y, "vertical follows the geometry")
        assertEquals(GazePoint(0.5f, -0.2f), ContentGaze.constrain(GazePoint(0.5f, -0.2f)))
    }

    // --- asides -------------------------------------------------------------------------------

    @Test
    fun atWorkAsidesAlwaysGoRightAndUp() {
        val steps = 10
        for (a in 0..steps) for (b in 0..steps) for (c in 0..steps) {
            val p = ContentGaze.aside(AWAY_REACH, atWork = true, ContentGaze.AsideDraws(a / 10f, b / 10f, c / 10f, 0.5f))
            assertTrue(p.x >= 0.45f, "right: $p")
            assertTrue(p.y <= 0f, "up: $p")
        }
    }

    @Test
    fun restingAsidesAreBiasedRightAndUpWithNoFarLeftDrift() {
        val rng = Random(42)
        var right = 0
        var up = 0
        val n = 4000
        repeat(n) {
            val draws = ContentGaze.AsideDraws(rng.nextFloat(), rng.nextFloat(), rng.nextFloat(), rng.nextFloat())
            val p = ContentGaze.aside(AWAY_REACH, atWork = false, draws)
            if (p.x > 0f) right++
            if (p.y <= 0f) up++
            assertTrue(p.x >= ContentGaze.MAX_LEFT_X, "never past the small left angle: $p")
        }
        assertTrue(right in (n * 70 / 100)..(n * 80 / 100), "~75 % right: $right / $n")
        assertTrue(up in (n * 70 / 100)..(n * 80 / 100), "~75 % up: $up / $n")
    }

    // --- the director -------------------------------------------------------------------------

    @Test
    fun aThinkingMascotSpendsMostOfItsTimeOnTheReplyUpAndRight() {
        val ticks = 3750 // 60 s
        var onTimelineAll = 0
        for (seed in 1..5) {
            val g = GazeDirector(Random(seed))
            val world = phoneWorld()
            var towardReply = 0
            var sumX = 0f
            var sumY = 0f
            repeat(ticks) {
                val pose = g.tick(0.016f, AvatarState.THINKING, world)
                if (pose.target == GazeTarget.TIMELINE) onTimelineAll++
                // Where the face points: the eyes plus the head turn they ride on.
                val x = pose.lookX + pose.headX
                val y = pose.lookY + pose.headY
                if (x > 0f) towardReply++
                sumX += x
                sumY += y
            }
            assertTrue(towardReply > ticks * 8 / 10, "seed $seed: facing right $towardReply / $ticks")
            assertTrue(sumX / ticks > 0.1f, "seed $seed: mean look is to the right ${sumX / ticks}")
            assertTrue(sumY / ticks < 0f, "seed $seed: mean look is up ${sumY / ticks}")
        }
        assertTrue(onTimelineAll > ticks * 5 / 2, "the reply is the main dwell: $onTimelineAll / ${ticks * 5}")
    }

    @Test
    fun noPlannedLookEverGoesLeftPastTheSmallAngle() {
        val states = listOf(
            AvatarState.IDLE, AvatarState.LISTENING, AvatarState.THINKING, AvatarState.WORKING,
            AvatarState.SPEAKING, AvatarState.WAITING_INPUT, AvatarState.ERROR,
        )
        for (seed in 1..3) for (state in states) {
            val g = GazeDirector(Random(seed))
            // A peer to the far left must not drag the eyes there either.
            val world = phoneWorld().copy(peers = listOf(GazePoint(-0.8f, 0f)))
            repeat(2000) {
                val pose = g.tick(0.016f, state, world)
                assertTrue(pose.lookX >= ContentGaze.MAX_LEFT_X - 1e-4f, "$state seed $seed: ${pose.lookX}")
            }
        }
    }

    @Test
    fun idleWanderingLeansRight() {
        val g = GazeDirector(Random(5))
        var right = 0
        var left = 0
        repeat(7500) { // 2 min
            val pose = g.tick(0.016f, AvatarState.IDLE, GazeWorld())
            if (pose.lookX > 0.02f) right++
            if (pose.lookX < -0.02f) left++
        }
        assertTrue(right > left * 3, "idle leans right: right $right vs left $left")
    }

    @Test
    fun reducedMotionHoldsOneLookOnTheContentWhileAtWork() {
        val g = GazeDirector(Random(9))
        val world = phoneWorld(reducedMotion = true)
        repeat(250) { g.tick(0.016f, AvatarState.THINKING, world) } // settle: 4 s
        val settled = g.lastPose
        assertEquals(GazeTarget.TIMELINE, settled.target)
        assertTrue(settled.lookX + settled.headX > 0f && settled.lookY + settled.headY < 0f, "up and right: $settled")
        repeat(625) { // 10 s more: no dwell switches, no saccades
            val pose = g.tick(0.016f, AvatarState.THINKING, world)
            assertEquals(GazeTarget.TIMELINE, pose.target)
            assertTrue(abs(pose.lookX - settled.lookX) < 0.01f && abs(pose.lookY - settled.lookY) < 0.01f, "held: $pose vs $settled")
            assertTrue(abs(pose.headX - settled.headX) < 0.01f, "head held: $pose vs $settled")
        }
    }

    @Test
    fun reducedMotionAtRestHoldsOnTheInput() {
        val g = GazeDirector(Random(9))
        val world = phoneWorld(reducedMotion = true)
        repeat(250) { g.tick(0.016f, AvatarState.IDLE, world) }
        val settled = g.lastPose
        repeat(625) {
            val pose = g.tick(0.016f, AvatarState.IDLE, world)
            assertEquals(GazeTarget.INPUT, pose.target)
            assertTrue(abs(pose.lookX - settled.lookX) < 0.01f && abs(pose.lookY - settled.lookY) < 0.01f, "held: $pose")
        }
    }

    @Test
    fun theGazeIsSmoothedNotSnapped() {
        val g = GazeDirector(Random(2))
        val world = phoneWorld()
        var last = g.tick(0.016f, AvatarState.THINKING, world)
        repeat(3750) {
            val pose = g.tick(0.016f, AvatarState.THINKING, world)
            // Even a scan saccade (the fastest ease) moves well under the whole range in one frame.
            assertTrue(abs(pose.headX - last.headX) < 0.05f, "head steps smoothly: ${last.headX} -> ${pose.headX}")
            last = pose
        }
    }
}
