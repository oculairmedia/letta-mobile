package com.letta.mobile.avatar.core

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-te3n1.2: attention points (caret, newest message, approval card, running tool), the
 * reaction cues that bias the next look, the THINKING look, the SPEC §10.4 containment clamp, the
 * seat hand-off's eye lead and the host blink dedupe.
 */
class GazeAttentionTest {
    private fun point(kind: AttentionKind, x: Float, y: Float, key: String = kind.name) =
        AttentionPoint(AttentionKey(key), kind, GazePoint(x, y))

    private fun GazeDirector.run(seconds: Float, state: AvatarState, world: GazeWorld, each: (GazePose) -> Unit = {}): GazePose {
        var pose = lastPose
        var left = seconds
        while (left > 0f) {
            pose = tick(STEP, state, world)
            each(pose)
            left -= STEP
        }
        return pose
    }

    @Test
    fun theApprovalCardWinsWithin300msOfTheApprovalBeingRaised() {
        val world = GazeWorld(
            timeline = GazePoint(0.4f, -0.3f),
            attention = listOf(point(AttentionKind.APPROVAL, 0.5f, -0.2f)),
        )
        repeat(SEEDS) { seed ->
            val gaze = GazeDirector(Random(seed))
            gaze.run(2f, AvatarState.WORKING, world)
            gaze.cue(GazeCue.APPROVAL_ASK)
            var sawCard = false
            gaze.run(0.3f, AvatarState.WAITING_INPUT, world) { if (it.target == GazeTarget.APPROVAL) sawCard = true }
            assertTrue(sawCard, "seed $seed: the card is looked at within 300 ms")
            assertEquals(GazeTarget.APPROVAL, gaze.target, "seed $seed: and it holds through the state flip")
        }
    }

    @Test
    fun anApprovalCueSnapsTheHeadWithABlink() {
        val gaze = GazeDirector(Random(1))
        val world = GazeWorld(attention = listOf(point(AttentionKind.APPROVAL, 0.8f, -0.4f)))
        gaze.run(1f, AvatarState.IDLE, world)
        gaze.cue(GazeCue.APPROVAL_ASK)
        val pose = gaze.tick(STEP, AvatarState.WAITING_INPUT, world)
        assertTrue(pose.blink, "the snap blinks")
    }

    @Test
    fun theMascotDirectorCuesTheCardOnApprovalRaised() = runTest {
        val runtime = HeadlessAvatarRuntime().also { it.load(AvatarModel(id = "m", displayName = "M", uri = "res://raw/mascot.riv")) }
        val director = MascotDirector(runtime, Random(4))
        director.setGazeWorld(GazeWorld(attention = listOf(point(AttentionKind.APPROVAL, 0.5f, -0.2f))))
        director.setSignals(MascotSignals(activity = AvatarActivity.WORKING))
        repeat(60) { director.tick(STEP) }
        director.onEvent(MascotEvent.ApprovalRaised)
        director.setSignals(MascotSignals(activity = AvatarActivity.WORKING, awaitingApproval = true))
        var sawCard = false
        repeat(18) { if (director.tick(STEP).gaze.target == GazeTarget.APPROVAL) sawCard = true }
        assertTrue(sawCard, "an approval turns the eyes to its card within 300 ms")
    }

    @Test
    fun theRunningToolDominatesWhileWorking() {
        val world = GazeWorld(
            timeline = GazePoint(0.3f, -0.5f),
            attention = listOf(point(AttentionKind.TOOL, 0.6f, -0.1f)),
        )
        val counts = IntArray(GazeTarget.entries.size)
        GazeDirector(Random(9)).run(60f, AvatarState.WORKING, world) { counts[it.target.ordinal]++ }
        val tool = counts[GazeTarget.TOOL.ordinal]
        val timeline = counts[GazeTarget.TIMELINE.ordinal]
        assertTrue(tool > timeline, "tool $tool should outweigh timeline $timeline")
        assertTrue(tool > counts.sum() * 0.35f, "tool $tool of ${counts.sum()} ticks")
    }

    @Test
    fun whileListeningTheEyesRideTheCaret() {
        val gaze = GazeDirector(ZeroRandom())
        val input = GazePoint(0.1f, 0.6f)
        val atStart = GazeWorld(input = input, attention = listOf(point(AttentionKind.CARET, 0.05f, 0.6f)))
        val first = gaze.run(1f, AvatarState.LISTENING, atStart)
        assertEquals(GazeTarget.CARET, first.target)
        // Typing walks the caret right; the eyes (and their typing scan) follow its x.
        val typed = GazeWorld(input = input, attention = listOf(point(AttentionKind.CARET, 0.55f, 0.6f)))
        val after = gaze.run(1.5f, AvatarState.LISTENING, typed)
        assertEquals(GazeTarget.CARET, after.target)
        assertTrue(after.lookX + after.headX > first.lookX + first.headX + 0.2f, "followed the caret: $first -> $after")
    }

    @Test
    fun theFieldsLookRidesTheCaretToo() {
        // A draw that lands on the INPUT row (after CARET's 50 of 85): the field's aim is the caret's place.
        val gaze = GazeDirector(ConstRandom(0.65f))
        val world = GazeWorld(input = GazePoint(0f, 0.6f), attention = listOf(point(AttentionKind.CARET, 0.6f, 0.6f)))
        val pose = gaze.run(1.5f, AvatarState.LISTENING, world)
        assertEquals(GazeTarget.INPUT, pose.target)
        assertTrue(pose.lookX + pose.headX > 0.3f, "the field's look follows the caret's x: $pose")
    }

    @Test
    fun thinkingAsidesLandUpAndRight() {
        val away = GazePlan.forState(AvatarState.THINKING).first { it.target == GazeTarget.AWAY }
        assertTrue(away.dwellSeconds.start >= 3f, "a thinking aside holds longer: ${away.dwellSeconds}")
        val random = Random(17)
        val upRight = (0 until 200).count {
            val aside = ContentGaze.aside(
                reach = GazeDirectorConfig().awayReach,
                atWork = ContentGaze.isAtWork(AvatarState.THINKING),
                draws = ContentGaze.AsideDraws(random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat()),
            )
            aside.x > 0f && aside.y <= 0f
        }
        assertTrue(upRight >= 140, "$upRight / 200 thinking asides up-right")
    }

    @Test
    fun containmentKeepsTheLookAndItsSaccadeInsideThePlate() {
        // The extremes: the pointer pinned to a corner, and a reply being read at the rim.
        val corner = GazeWorld(pointer = GazePoint(1f, 1f), mode = GazeDriveMode.CURSOR)
        GazeDirector(Random(2)).run(3f, AvatarState.IDLE, corner) { assertTrue(GazeContainment.fits(it.lookX, it.lookY), "corner look $it") }
        val rim = GazeWorld(timeline = GazePoint(1f, -1f))
        GazeDirector(Random(2)).run(20f, AvatarState.SPEAKING, rim) { assertTrue(GazeContainment.fits(it.lookX, it.lookY), "reading look $it") }
    }

    @Test
    fun containmentAttenuatesTheSaccadeBeforeTheIntent() {
        val host = GazePoint(0.7f, 0f)
        assertTrue(GazeContainment.fits(host.x, host.y))
        val lambda = GazeContainment.noiseScale(host, 0.3f, 0f)
        assertTrue(lambda in 0f..0.999f, "the saccade is cut: $lambda")
        assertTrue(GazeContainment.fits(host.x + lambda * 0.3f, 0f))
        assertEquals(1f, GazeContainment.noiseScale(GazePoint(0f, 0f), 0.1f, 0.1f), "a small look keeps its saccade")
        val kappa = GazeContainment.hostScale(GazePoint(1f, 0f))
        assertTrue(kappa < 1f && GazeContainment.fits(kappa, 0f), "an extreme intent is scaled in: $kappa")
        assertEquals(0f, GazeContainment.noiseScale(GazePoint(1f, 0f), 0.1f, 0f), "no noise while the intent itself does not fit")
    }

    @Test
    fun theEyesLeadBeforeAHandOffAndNothingResetsOnArrival() {
        val gaze = GazeDirector(ZeroRandom())
        val world = GazeWorld()
        gaze.run(0.5f, AvatarState.IDLE, world)
        val before = gaze.target
        gaze.lead(GazePoint(0.8f, -0.4f), 0.45f)
        val during = gaze.run(0.3f, AvatarState.IDLE, world)
        assertTrue(during.lookX > 0.3f && during.lookY < -0.1f, "eyes on the next seat: $during")
        assertEquals(before, gaze.target, "the plan keeps its look underneath the lead")
        gaze.run(0.5f, AvatarState.IDLE, world)
        assertEquals(before, gaze.target, "arriving resets nothing")
    }

    @Test
    fun reducedMotionHoldsOnWhatTheStateIsAbout() {
        val world = GazeWorld(
            input = GazePoint(0f, 0.6f),
            timeline = GazePoint(0.4f, -0.4f),
            attention = listOf(
                point(AttentionKind.APPROVAL, 0.5f, -0.2f),
                point(AttentionKind.TOOL, 0.6f, -0.1f),
                point(AttentionKind.CARET, 0.2f, 0.6f),
            ),
            reducedMotion = true,
        )
        val cases = mapOf(
            AvatarState.WAITING_INPUT to GazeTarget.APPROVAL,
            AvatarState.WORKING to GazeTarget.TOOL,
            AvatarState.LISTENING to GazeTarget.CARET,
            AvatarState.SPEAKING to GazeTarget.TIMELINE,
        )
        for ((state, expected) in cases) {
            val targets = mutableSetOf<GazeTarget>()
            GazeDirector(Random(3)).run(5f, state, world) { targets += it.target }
            assertEquals(setOf(expected), targets, "$state holds one look")
        }
    }

    @Test
    fun interestDecaysAndANewSourceStartsAgain() {
        assertEquals(1f, AttentionInterest.APPROVAL.at(0f))
        assertEquals(0.7f, AttentionInterest.APPROVAL.at(2f), 1e-4f)
        assertEquals(0.4f, AttentionInterest.APPROVAL.at(10f), 1e-4f)
        assertEquals(1f, AttentionInterest.MESSAGE.at(3f))
        assertEquals(0.3f, AttentionInterest.MESSAGE.at(6f), 1e-4f)
        val clock = AttentionClock()
        val first = point(AttentionKind.APPROVAL, 0f, 0f, key = "a")
        // The first tick a point is seen it is new (age 0); four more make it 4 s old.
        repeat(5) { clock.advance(listOf(first), 1f) }
        assertEquals(0.4f, clock.interestOf(first), 1e-4f)
        val next = point(AttentionKind.APPROVAL, 0f, 0f, key = "b")
        clock.advance(listOf(next), 1f)
        assertEquals(1f, clock.interestOf(next), "a new card starts at full pull")
    }

    @Test
    fun fromWindowKeepsTheNewestOfEachKindAndReadsMessagesAtTheirLiveEdge() {
        val mascot = GazeRect(0f, 500f, 64f, 564f)
        val world = GazeWorld.fromWindow(
            GazeWindow(
                mascot = mascot,
                reach = GazeReach(360f),
                rects = GazeTargetRects(
                    attention = listOf(
                        AttentionRect(AttentionKey("old"), AttentionKind.APPROVAL, GazeRect(100f, 0f, 300f, 100f)),
                        AttentionRect(AttentionKey("new"), AttentionKind.APPROVAL, GazeRect(100f, 300f, 300f, 400f)),
                        AttentionRect(AttentionKey("msg"), AttentionKind.MESSAGE, GazeRect(100f, 0f, 300f, 400f)),
                        AttentionRect(AttentionKey("gone"), AttentionKind.TOOL, GazeRect(0f, 0f, 0f, 0f)),
                    ),
                ),
            ),
        )
        assertEquals("new", world.attentionOf(AttentionKind.APPROVAL)?.key?.value)
        assertNull(world.attentionOf(AttentionKind.TOOL), "an empty rect is no target")
        val message = assertNotNull(world.attentionOf(AttentionKind.MESSAGE)).point
        val center = GazeMath.pointerToGaze(200f, 200f, mascot, 360f)
        assertTrue(message.y > center.y, "the live edge is low in the message: $message vs centre $center")
    }

    @Test
    fun hostBlinksAreNeverCloserThan250ms() = runTest {
        var blinks = 0
        val runtime = object : HeadlessAvatarRuntime() {
            override fun onPlayGesture(gesture: AvatarGesture, fadeSeconds: Float) {
                if (gesture.id == "blink") blinks++
            }
        }.also { it.load(AvatarModel(id = "m", displayName = "M", uri = "res://raw/mascot.riv")) }
        val director = MascotDirector(runtime, Random(7), avatarConfig = AvatarDirector.Config(blinkMinInterval = 1_000f, blinkMaxInterval = 1_000f))
        director.setSignals(MascotSignals(reduceMotion = true))
        director.onEvent(MascotEvent.Sent)
        director.onEvent(MascotEvent.ApprovalRaised)
        assertEquals(1, blinks, "two reactions at once blink once")
        repeat(10) { director.tick(STEP) }
        director.onEvent(MascotEvent.RunFailed)
        assertEquals(1, blinks, "160 ms later is still too soon")
        repeat(6) { director.tick(STEP) }
        director.onEvent(MascotEvent.Poke)
        assertEquals(2, blinks, "past 250 ms it blinks again")
    }

    private class ConstRandom(private val value: Float) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextFloat(): Float = value
        override fun nextInt(until: Int): Int = 0
    }

    private class ZeroRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextFloat(): Float = 0f
        override fun nextInt(until: Int): Int = 0
    }

    private companion object {
        const val STEP = 1f / 60f
        const val SEEDS = 20
    }
}
