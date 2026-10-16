package com.letta.mobile.avatar.core

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-te3n1.1: the shared director's transition and reaction table, walked with a seeded
 * Random and fixed steps - every event mapping and every honest-state fix has a row here.
 */
class MascotDirectorTest {
    private class RecordingRuntime : HeadlessAvatarRuntime() {
        val reactions = mutableListOf<AvatarReaction>()
        var blinks = 0
        var lastListenLevel = 0f

        override fun react(reaction: AvatarReaction, arg: Float) {
            reactions += reaction
        }

        override fun setListenLevel(level: Float) {
            lastListenLevel = level
        }

        override fun onPlayGesture(gesture: AvatarGesture, fadeSeconds: Float) {
            if (gesture.id == "blink") blinks++
        }
    }

    private class Rig(val runtime: RecordingRuntime, val director: MascotDirector) {
        val entered = mutableListOf<AvatarState>()
        var signals = MascotSignals()

        init {
            director.addStateListener { _, enter -> entered += enter }
        }

        fun signals(transform: MascotSignals.() -> MascotSignals) {
            signals = signals.transform()
            director.setSignals(signals)
        }

        fun tick(seconds: Float, step: Float = 0.016f) {
            var left = seconds
            while (left > 0f) {
                director.tick(minOf(step, left))
                left -= step
            }
        }
    }

    private suspend fun rig(): Rig {
        val runtime = RecordingRuntime().also { it.load(AvatarModel(id = "m", displayName = "M", uri = "res://raw/mascot.riv")) }
        // A blink schedule far beyond any test keeps the blink counts about reactions only.
        val avatar = AvatarDirector.Config(blinkMinInterval = 1_000f, blinkMaxInterval = 1_000f)
        return Rig(runtime, MascotDirector(runtime, Random(7), avatarConfig = avatar))
    }

    @Test
    fun typingListensAndTheCadenceSetsHowIntently() = runTest {
        val rig = rig()
        rig.signals { copy(userTyping = true) }
        assertEquals(AvatarState.LISTENING, rig.director.state)

        rig.director.setKeystrokes(0)
        rig.tick(1f)
        val paused = rig.director.frame.listenLevel
        assertTrue(paused in 0.2f..0.3f, "a composing user who pauses still has some attention: $paused")

        // Ten keystrokes a second, one a frame-burst, for a second.
        var keys = 0
        repeat(60) { frame ->
            if (frame % 6 == 0) keys++
            rig.director.setKeystrokes(keys)
            rig.director.tick(0.016f)
        }
        assertTrue(rig.director.frame.listenLevel > paused + 0.2f, "fast typing listens harder: ${rig.director.frame.listenLevel}")
        assertEquals(rig.director.frame.listenLevel, rig.runtime.lastListenLevel, 0.011f)

        rig.signals { copy(userTyping = false) }
        rig.tick(3f)
        assertEquals(0f, rig.director.frame.listenLevel)
    }

    @Test
    fun aSendNodsThenThinks() = runTest {
        val rig = rig()
        rig.signals { copy(activity = AvatarActivity.THINKING) }
        rig.director.onEvent(MascotEvent.Sent)
        assertEquals(listOf(AvatarReaction.SENT), rig.runtime.reactions)
        assertEquals(AvatarState.THINKING, rig.director.state)
        rig.director.tick(0.016f)
        assertTrue(rig.director.frame.fired(AvatarReaction.SENT))
        rig.director.tick(0.016f)
        assertEquals(0, rig.director.frame.reactionCount, "a reaction is reported for the frame it fired in")
    }

    @Test
    fun aToolStormIsOneBeatAndOneSteadyWorkingState() = runTest {
        val rig = rig()
        rig.signals { copy(activity = AvatarActivity.THINKING) }
        repeat(10) { i ->
            rig.signals { copy(activity = AvatarActivity.WORKING, toolName = "t$i") }
            rig.director.onEvent(MascotEvent.ToolStarted("t$i"))
            rig.tick(0.05f)
            rig.signals { copy(activity = AvatarActivity.THINKING) }
            rig.director.onEvent(MascotEvent.ToolEnded)
            rig.tick(0.05f)
        }
        assertEquals(1, rig.runtime.reactions.count { it == AvatarReaction.TOOL_START })
        assertEquals(1, rig.runtime.reactions.count { it == AvatarReaction.TOOL_DONE })
        assertEquals(listOf(AvatarState.THINKING, AvatarState.WORKING), rig.entered, "no strobe back to thinking")
        rig.tick(1.6f)
        assertEquals(AvatarState.THINKING, rig.director.state, "released after the 1.5 s dwell")
    }

    @Test
    fun anApprovalAsksAndWaits() = runTest {
        val rig = rig()
        rig.signals { copy(activity = AvatarActivity.WORKING, awaitingApproval = true) }
        rig.director.onEvent(MascotEvent.ApprovalRaised)
        assertEquals(AvatarState.WAITING_INPUT, rig.director.state)
        assertEquals(listOf(AvatarReaction.APPROVAL_ASK), rig.runtime.reactions)
    }

    @Test
    fun everyCompletionCelebratesBackToBackIncluded() = runTest {
        val rig = rig()
        rig.signals { copy(activity = AvatarActivity.SPEAKING) }
        rig.signals { copy(activity = AvatarActivity.IDLE) }
        rig.director.onEvent(MascotEvent.RunFinished(ok = true))
        assertEquals(AvatarState.SUCCESS, rig.director.state)
        rig.tick(0.3f)
        rig.signals { copy(activity = AvatarActivity.SPEAKING) }
        rig.signals { copy(activity = AvatarActivity.IDLE) }
        rig.director.onEvent(MascotEvent.RunFinished(ok = true))
        assertEquals(2, rig.runtime.reactions.count { it == AvatarReaction.RUN_DONE }, "${rig.runtime.reactions}")
    }

    @Test
    fun aCancelIsNotASuccess() = runTest {
        val rig = rig()
        rig.director.onEvent(MascotEvent.RunFinished(ok = false))
        assertTrue(rig.runtime.reactions.isEmpty())
        assertEquals(AvatarState.IDLE, rig.director.state)
    }

    @Test
    fun aFailureFlashesAndTheErrorSurvivesTyping() = runTest {
        // The latch bug: every presence change re-sent the activity, which cleared the error.
        val rig = rig()
        rig.signals { copy(activity = AvatarActivity.THINKING) }
        rig.signals { copy(activity = AvatarActivity.IDLE, error = true) }
        rig.director.onEvent(MascotEvent.RunFailed)
        assertEquals(AvatarState.ERROR, rig.director.state)
        assertEquals(listOf(AvatarReaction.RUN_FAILED), rig.runtime.reactions)
        assertEquals(listOf(AvatarState.THINKING, AvatarState.ERROR), rig.entered, "one transition, no flash of idle")

        repeat(3) {
            rig.signals { copy(userTyping = true) }
            rig.tick(0.2f)
            rig.signals { copy(userTyping = false) }
            rig.tick(1f)
        }
        assertEquals(AvatarState.ERROR, rig.director.state)

        // A new send is what replaces the failure.
        rig.signals { copy(error = false, activity = AvatarActivity.THINKING) }
        rig.director.onEvent(MascotEvent.Sent)
        assertEquals(AvatarState.THINKING, rig.director.state)
        assertEquals(AvatarReaction.SENT, rig.runtime.reactions.last())
    }

    @Test
    fun noSendNodWhileAnErrorStands() = runTest {
        val rig = rig()
        rig.signals { copy(error = true) }
        rig.director.onEvent(MascotEvent.Sent)
        assertTrue(AvatarReaction.SENT !in rig.runtime.reactions)
    }

    @Test
    fun aDreamIsItsOwnStateAndAPokeWakesItFirst() = runTest {
        val rig = rig()
        rig.signals { copy(dreaming = true) }
        assertEquals(AvatarState.DREAMING, rig.director.state)
        assertEquals(listOf(AvatarReaction.YAWN), rig.runtime.reactions)

        rig.tick(0.5f)
        rig.director.onEvent(MascotEvent.Poke)
        assertEquals(listOf(AvatarReaction.YAWN, AvatarReaction.WAKE, AvatarReaction.POKE), rig.runtime.reactions)
        rig.director.tick(0.016f)
        assertEquals(AvatarReaction.WAKE, rig.director.frame.reactionAt(0))
        assertEquals(AvatarReaction.POKE, rig.director.frame.reactionAt(1))
    }

    @Test
    fun aTurnWakesADreamerBeforeItNods() = runTest {
        val rig = rig()
        rig.signals { copy(dreaming = true) }
        rig.tick(0.5f)
        rig.signals { copy(activity = AvatarActivity.THINKING) }
        rig.director.onEvent(MascotEvent.Sent)
        assertEquals(listOf(AvatarReaction.YAWN, AvatarReaction.WAKE, AvatarReaction.SENT), rig.runtime.reactions)
        assertEquals(AvatarState.THINKING, rig.director.state)
    }

    @Test
    fun fivePokesInTwoSecondsMakeItDizzy() = runTest {
        val rig = rig()
        repeat(5) {
            rig.director.onEvent(MascotEvent.Poke)
            rig.tick(0.2f)
        }
        assertEquals(5, rig.runtime.reactions.count { it == AvatarReaction.POKE })
        assertEquals(1, rig.runtime.reactions.count { it == AvatarReaction.DIZZY })
    }

    @Test
    fun reducedMotionPlaysEveryReactionAsABlink() = runTest {
        val rig = rig()
        rig.signals { copy(reduceMotion = true, activity = AvatarActivity.THINKING) }
        rig.director.onEvent(MascotEvent.Sent)
        rig.signals { copy(activity = AvatarActivity.IDLE) }
        rig.director.onEvent(MascotEvent.RunFinished(ok = true))
        assertTrue(rig.runtime.reactions.isEmpty(), "no reaction animation under reduced motion: ${rig.runtime.reactions}")
        assertEquals(2, rig.runtime.blinks)
        rig.director.tick(0.016f)
        assertTrue(rig.director.frame.reduceMotion)
        assertTrue(rig.director.frame.fired(AvatarReaction.RUN_DONE), "the frame still says what happened")
    }

    @Test
    fun aDragHoldsDraggedCarriesTheHostsVelocityAndLandsOnRelease() = runTest {
        // Option A: the host measures the drag; the director writes dragged and the motion.
        val rig = rig()
        rig.director.onEvent(MascotEvent.DragStarted)
        assertEquals(AvatarState.DRAGGED, rig.director.state)
        rig.director.onEvent(MascotEvent.Motion(vx = 1.5f, vy = -0.5f))
        assertEquals(1.5f, rig.director.frame.motionX)
        assertEquals(-0.5f, rig.director.frame.motionY)
        rig.director.onEvent(MascotEvent.DragEnded(impact = 2f))
        assertEquals(0f, rig.director.frame.motionX, "put down: at rest")
        assertEquals(AvatarState.IDLE, rig.director.state)
        assertEquals(listOf(AvatarReaction.LAND), rig.runtime.reactions)
    }

    @Test
    fun hoverAndPettingAreLevelsOnTheFrame() = runTest {
        val rig = rig()
        rig.director.onEvent(MascotEvent.Hover(hovered = true))
        rig.director.onEvent(MascotEvent.Petting(active = true))
        rig.director.onEvent(MascotEvent.DoubleTap(trick = 2))
        assertTrue(rig.director.frame.hovered)
        assertTrue(rig.director.frame.petting)
        assertEquals(listOf(AvatarReaction.TRICK), rig.runtime.reactions)
    }
}
