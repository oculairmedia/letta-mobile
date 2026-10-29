package com.letta.mobile.avatar.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GazeCalmTest {
    private val workWorld = GazeWorld(
        timeline = GazePoint(0.35f, -0.3f),
        attention = listOf(
            AttentionPoint(AttentionKey("tool"), AttentionKind.TOOL, GazePoint(0.7f, -0.1f)),
            AttentionPoint(AttentionKey("message"), AttentionKind.MESSAGE, GazePoint(0.6f, -0.4f)),
        ),
    )
    private val workStates = listOf(AvatarState.THINKING, AvatarState.WORKING, AvatarState.SPEAKING)

    @Test
    fun rapidWorkStateFlipsKeepOnePlanAndRespectTheSaccadeCap() {
        repeat(10) { seed ->
            val config = GazeDirectorConfig().apply { eyeHeadCompensation = 0f }
            val gaze = GazeDirector(Random(seed), config)
            val steady = GazeDirector(Random(seed), config)
            var previous = gaze.lastPose
            var previousAim = GazePoint(0f, 0f)
            var lastChange = -1_000
            var changes = 0
            var flip = 0
            var nextFlip = 10
            repeat(1_000) { frame -> // 10 seconds, state flips every 100 / 200 / 300 ms.
                if (frame == nextFlip) {
                    flip++
                    nextFlip += 10 + flip % 3 * 10
                }
                val state = workStates[flip % workStates.size]
                val pose = gaze.tick(STEP, state, workWorld)
                val aim = fixation(pose, previous, config)
                if (pose.target != previous.target || changed(aim, previousAim)) {
                    assertTrue((frame - lastChange) * STEP >= 1f / config.maxSaccadesPerSecond - EPSILON)
                    lastChange = frame
                    changes++
                }
                assertEquals(GazeTarget.TIMELINE, pose.target, "seed $seed, frame $frame, state $state")
                // Even the drift schedule/random draws survive flips: the plan never restarts.
                assertEquals(steady.tick(STEP, AvatarState.THINKING, workWorld), pose, "seed $seed, frame $frame")
                previous = pose
                previousAim = aim
            }
            assertEquals(2, changes, "one live-edge fixation and one drift in ten seconds, seed $seed")
        }
    }

    @Test
    fun briefRestStateDoesNotRestartButSustainedRestDoes() {
        val gaze = GazeDirector(ZeroRandom())
        repeat(100) { gaze.tick(STEP, AvatarState.THINKING, workWorld) }
        repeat(30) {
            assertEquals(GazeTarget.TIMELINE, gaze.tick(STEP, AvatarState.IDLE, workWorld).target)
        }
        repeat(100) { gaze.tick(STEP, AvatarState.THINKING, workWorld) }
        assertEquals(GazeTarget.TIMELINE, gaze.target)
        repeat(49) {
            assertEquals(GazeTarget.TIMELINE, gaze.tick(STEP, AvatarState.IDLE, workWorld).target)
        }
        repeat(20) { gaze.tick(STEP, AvatarState.IDLE, workWorld) }
        assertEquals(GazeTarget.AWAY, gaze.target)
    }

    @Test
    fun enteringWorkDebouncesTheWholeWorkModeAcrossStateFlips() {
        val gaze = GazeDirector(ZeroRandom())
        repeat(100) { gaze.tick(STEP, AvatarState.IDLE, workWorld) }
        repeat(49) { frame ->
            assertEquals(GazeTarget.AWAY, gaze.tick(STEP, workStates[frame / 10 % 3], workWorld).target)
        }
        repeat(20) { frame -> gaze.tick(STEP, workStates[frame / 10 % 3], workWorld) }
        assertEquals(GazeTarget.TIMELINE, gaze.target, "work-mode debounce must not restart on each work state")
    }

    @Test
    fun approvalCueAndPointerDemandSkipStateDebounce() {
        val gaze = GazeDirector(ZeroRandom())
        repeat(100) { gaze.tick(STEP, AvatarState.THINKING, workWorld) }
        gaze.cue(GazeCue.APPROVAL_ASK)
        val approval = workWorld.copy(
            attention = workWorld.attention +
                AttentionPoint(AttentionKey("approval"), AttentionKind.APPROVAL, GazePoint(0.8f, -0.2f)),
        )
        val pose = gaze.tick(STEP, AvatarState.WAITING_INPUT, approval)
        assertEquals(GazeTarget.APPROVAL, pose.target, "cue takes the next tick without a state hold")
        assertTrue(pose.blink, "the head snap belongs to the committed approval fixation")
        repeat(100) { gaze.tick(STEP, AvatarState.WAITING_INPUT, approval) }
        val withPointer = approval.copy(pointer = GazePoint(0.2f, 0.1f), pointerMovedRecently = true)
        assertEquals(GazeTarget.CURSOR, gaze.tick(STEP, AvatarState.WAITING_INPUT, withPointer).target)
    }

    @Test
    fun aCueDuringAFixationWaitsForItsSlotAndKeepsItsHeadSnap() {
        val gaze = GazeDirector(ZeroRandom())
        assertEquals(GazeTarget.TIMELINE, gaze.tick(STEP, AvatarState.WORKING, workWorld).target)
        gaze.cue(GazeCue.APPROVAL_ASK)
        repeat(65) {
            val pose = gaze.tick(STEP, AvatarState.WAITING_INPUT, workWorld)
            assertEquals(GazeTarget.TIMELINE, pose.target)
            assertTrue(!pose.blink, "a queued cue must not snap the head at the old target")
        }
        var pose = gaze.lastPose
        var blinks = 0
        repeat(3) {
            pose = gaze.tick(STEP, AvatarState.WAITING_INPUT, workWorld)
            if (pose.blink) blinks++
        }
        assertEquals(GazeTarget.USER, pose.target, "approval without a card looks at the user at the next slot")
        assertEquals(1, blinks, "the queued approval keeps its head snap")
    }

    @Test
    fun anInferredPointerDemandSurvivesUntilTheNextFixationSlot() {
        val gaze = GazeDirector(ZeroRandom())
        gaze.tick(STEP, AvatarState.WORKING, workWorld)
        val pointer = workWorld.copy(pointer = GazePoint(0.2f, 0.1f))
        repeat(65) {
            assertEquals(GazeTarget.TIMELINE, gaze.tick(STEP, AvatarState.WORKING, pointer).target)
        }
        repeat(3) { gaze.tick(STEP, AvatarState.WORKING, pointer) }
        assertEquals(GazeTarget.CURSOR, gaze.target, "the demand must outlast the cap's remaining hold")
    }

    @Test
    fun typingAndMutualGazeScansShareTheCap() {
        assertRateLimited({ GazeWorld(input = GazePoint(0.3f, 0.2f)) })
        assertRateLimited({ GazeWorld() })
        assertRateLimited({ GazeWorld(input = GazePoint(0.3f, 0.2f)) }, rate = 0.75f)
    }

    @Test
    fun cuesPointersGeometryAndHandOffLeadsCannotBypassTheCap() {
        assertRateLimited(
            worldAt = { frame ->
                workWorld.copy(
                    timeline = GazePoint(0.3f + frame % 5 * 0.02f, -0.3f),
                    pointer = if (frame % 14 < 7) GazePoint(0.2f, 0.1f + frame % 3 * 0.02f) else null,
                    pointerMovedRecently = true,
                    mode = if (frame % 100 < 10) GazeDriveMode.CURSOR else GazeDriveMode.JUSTIFIED,
                )
            },
            beforeTick = { gaze, frame ->
                if (frame % 17 == 0) gaze.cue(if (frame % 2 == 0) GazeCue.APPROVAL_ASK else GazeCue.SENT)
                if (frame % 31 == 0) gaze.lead(GazePoint(0.6f, -0.2f), 0.45f)
            },
        )
    }

    @Test
    fun sameTargetPlanChangesAndMovingCaretsCannotBypassTheCap() {
        assertRateLimited({ GazeWorld() }, state = AvatarState.IDLE, rate = 0.2f)
        assertRateLimited({ frame ->
            GazeWorld(
                attention = listOf(
                    AttentionPoint(AttentionKey("caret"), AttentionKind.CARET, GazePoint(0.3f + frame % 5 * 0.02f, 0.2f)),
                ),
            )
        })
    }

    @Test
    fun messageGeometryAndIdentityDoNotInventANewTextScan() {
        val config = GazeDirectorConfig().apply { workDriftReach = 0f; eyeHeadCompensation = 0f }
        val gaze = GazeDirector(ZeroRandom(), config)
        var previous = gaze.lastPose
        repeat(1_000) { frame ->
            val world = workWorld.copy(
                timeline = null,
                attention = listOf(
                    AttentionPoint(AttentionKey("message-${frame / 20}"), AttentionKind.MESSAGE, GazePoint(0.6f, -0.4f)),
                ),
            )
            val pose = gaze.tick(STEP, AvatarState.SPEAKING, world)
            assertEquals(GazeTarget.MESSAGE, pose.target)
            val aim = fixation(pose, previous, config)
            assertEquals(0.6f, aim.x, EPSILON)
            assertEquals(-0.4f, aim.y, EPSILON)
            previous = pose
        }
    }

    @Test
    fun invalidRatesCannotDisableTheCap() {
        for (rate in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { GazeDirectorConfig().maxSaccadesPerSecond = rate }
        }
    }

    private fun assertRateLimited(
        worldAt: (Int) -> GazeWorld,
        state: AvatarState = AvatarState.LISTENING,
        rate: Float = 1.5f,
        beforeTick: (GazeDirector, Int) -> Unit = { _, _ -> },
    ) {
        val config = GazeDirectorConfig().apply { maxSaccadesPerSecond = rate; eyeHeadCompensation = 0f }
        val gaze = GazeDirector(ZeroRandom(), config)
        var previous = gaze.lastPose
        var previousAim = GazePoint(0f, 0f)
        var lastChange = -10_000
        var changes = 0
        repeat(2_000) { frame ->
            beforeTick(gaze, frame)
            val pose = gaze.tick(STEP, state, worldAt(frame))
            val aim = fixation(pose, previous, config)
            if (pose.target != previous.target || changed(aim, previousAim)) {
                assertTrue(
                    (frame - lastChange) * STEP >= 1f / rate - EPSILON,
                    "fixation changed at $frame after $lastChange, rate $rate: $previousAim -> $aim",
                )
                changes++
                lastChange = frame
            }
            previous = pose
            previousAim = aim
        }
        assertTrue(changes > 1, "the rate check must exercise actual fixation changes")
        assertTrue(changes <= 1 + 20 * rate, "$changes fixations in 20 s at $rate/s")
    }

    // Undo the eye easing to measure fixation changes, rather than counting animation frames.
    // Compensation is disabled in these tests; the points stay away from containment/clamp edges.
    private fun fixation(pose: GazePose, previous: GazePose, config: GazeDirectorConfig): GazePoint {
        val scanning = pose.target == GazeTarget.INPUT || pose.target == GazeTarget.CARET
        val tau = if (scanning) config.scanTauSeconds else config.eyeTauSeconds
        val k = 1f - exp(-STEP / tau)
        return GazePoint(
            previous.lookX + (pose.lookX - previous.lookX) / k,
            previous.lookY + (pose.lookY - previous.lookY) / k,
        )
    }

    private fun changed(first: GazePoint, second: GazePoint): Boolean =
        abs(first.x - second.x) > EPSILON || abs(first.y - second.y) > EPSILON

    private class ZeroRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextFloat(): Float = 0f
        override fun nextInt(until: Int): Int = 0
    }

    private companion object {
        const val STEP = 0.01f
        const val EPSILON = 0.0001f
    }

    @Test
    fun invalidCalmTunablesAreRejected() {
        val config = GazeDirectorConfig()
        kotlin.test.assertFailsWith<IllegalArgumentException> { config.stateDebounceSeconds = Float.NaN }
        kotlin.test.assertFailsWith<IllegalArgumentException> { config.stateDebounceSeconds = -1f }
        kotlin.test.assertFailsWith<IllegalArgumentException> { config.workDriftReach = Float.POSITIVE_INFINITY }
        kotlin.test.assertFailsWith<IllegalArgumentException> { config.workDriftReach = -0.1f }
        kotlin.test.assertFailsWith<IllegalArgumentException> { config.workDriftSeconds = Float.NaN..9f }
        kotlin.test.assertFailsWith<IllegalArgumentException> { config.workDriftSeconds = 0f..9f }
    }
}
