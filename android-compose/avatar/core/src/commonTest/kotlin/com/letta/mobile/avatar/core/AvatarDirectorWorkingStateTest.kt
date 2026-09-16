package com.letta.mobile.avatar.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * WORKING (letta-mobile-z4b83): tool execution is its own state, held long enough that the
 * reasoning/tool alternation inside one turn cannot strobe the mascot.
 */
class AvatarDirectorWorkingStateTest {
    private val model = AvatarModel(id = "avatar-1", displayName = "Buddy", uri = "res://raw/mascot.riv")

    private suspend fun director(): AvatarDirector {
        val runtime = HeadlessAvatarRuntime().also { it.load(model) }
        return AvatarDirector(runtime, Random(1), AvatarDirector.Config(blinkMinInterval = 1f, blinkMaxInterval = 1f))
    }

    @Test
    fun aToolCallEntersWorkingAndOutranksThinking() = runTest {
        val director = director()
        director.setActivity(AvatarActivity.THINKING)
        assertEquals(AvatarState.THINKING, director.state)
        director.setActivity(AvatarActivity.WORKING)
        assertEquals(AvatarState.WORKING, director.state)
    }

    @Test
    fun delegatingReadsAsWorkingUntilItsOwnLook() = runTest {
        val director = director()
        director.setActivity(AvatarActivity.DELEGATING)
        assertEquals(AvatarState.WORKING, director.state)
    }

    @Test
    fun theQuietGapKeepsToolThinkingToolAlternationSteady() = runTest {
        val director = director()
        director.setActivity(AvatarActivity.WORKING)
        assertEquals(AvatarState.WORKING, director.state)

        // The gap between two tool calls: reasoning tokens for a third of a second.
        director.setActivity(AvatarActivity.THINKING)
        director.tick(0.3f)
        assertEquals(AvatarState.WORKING, director.state, "a short gap must not flip the mascot back")

        director.setActivity(AvatarActivity.WORKING)
        director.tick(0.3f)
        assertEquals(AvatarState.WORKING, director.state)
    }

    @Test
    fun workingReleasesAfterItsMinimumDwellOnceTheToolIsDone() = runTest {
        val director = director()
        director.setActivity(AvatarActivity.WORKING)
        director.setActivity(AvatarActivity.THINKING)
        director.tick(1.0f)
        assertEquals(AvatarState.WORKING, director.state, "still inside the 1.5s dwell")
        director.tick(0.6f)
        assertEquals(AvatarState.THINKING, director.state)
    }

    @Test
    fun tokensAndApprovalsStillOutrankWorking() = runTest {
        val director = director()
        director.setActivity(AvatarActivity.WORKING)
        director.setActivity(AvatarActivity.SPEAKING)
        assertEquals(AvatarState.SPEAKING, director.state, "an actual reply beats a lingering tool")

        director.setActivity(AvatarActivity.WORKING)
        director.setAwaitingApproval(true)
        assertEquals(AvatarState.WAITING_INPUT, director.state)
        director.setAwaitingApproval(false)
        assertEquals(AvatarState.WORKING, director.state)
    }

    @Test
    fun theWorkingRingIsAmberAndFasterThanThinking() {
        val working = PresenceSemantics.cueFor(AvatarState.WORKING)
        val thinking = PresenceSemantics.cueFor(AvatarState.THINKING)
        assertEquals(thinking.color, working.color)
        assertEquals(true, (working.durationMillis ?: 0) < (thinking.durationMillis ?: 0))
    }
}
