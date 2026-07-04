package com.letta.mobile.avatar.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Deterministic coverage of the 12-state director (PRD §4 P2, §6): priority
 * arbitration, SLEEPING gating, SUCCESS self-expiry, LISTENING release, the
 * lifecycle feed, transition-listener ordering, and the §6 timing constants
 * observed through recorded runtime weights.
 */
class AvatarDirectorStateMachineTest {
    private val model = AvatarModel(
        id = "avatar-1",
        displayName = "Buddy",
        uri = "file:///buddy.vrm",
        format = AvatarFormat.VRM_1,
    )

    private suspend fun readyRuntime(): HeadlessAvatarRuntime =
        HeadlessAvatarRuntime().also { it.load(model) }

    private fun fixedBlinkConfig() = AvatarDirector.Config(
        blinkMinInterval = 1f,
        blinkMaxInterval = 1f,
        blinkCloseSeconds = 0.1f,
    )

    private suspend fun director(): Pair<HeadlessAvatarRuntime, AvatarDirector> {
        val runtime = readyRuntime()
        return runtime to AvatarDirector(runtime, Random(1), fixedBlinkConfig())
    }

    // ---- priority arbitration ------------------------------------------------

    @Test
    fun draggedBeatsEverything() = runTest {
        val (_, d) = director()
        d.setActivity(AvatarActivity.SPEAKING)
        d.setAwaitingApproval(true)
        d.notifyError()
        d.setDragged(true)
        assertEquals(AvatarState.DRAGGED, d.state)
    }

    @Test
    fun errorBeatsWaitingSpeakingThinking() = runTest {
        val (_, d) = director()
        d.setAwaitingApproval(true)
        d.setActivity(AvatarActivity.SPEAKING)
        d.notifyError()
        assertEquals(AvatarState.ERROR, d.state)
    }

    @Test
    fun waitingInputBeatsSpeaking() = runTest {
        val (_, d) = director()
        d.setActivity(AvatarActivity.SPEAKING)
        d.setAwaitingApproval(true)
        assertEquals(AvatarState.WAITING_INPUT, d.state)
        d.setAwaitingApproval(false)
        assertEquals(AvatarState.SPEAKING, d.state)
    }

    @Test
    fun speakingBeatsSuccess() = runTest {
        val (_, d) = director()
        d.notifyTaskSucceeded()
        d.setActivity(AvatarActivity.SPEAKING)
        assertEquals(AvatarState.SPEAKING, d.state)
    }

    @Test
    fun successBeatsThinking() = runTest {
        val (_, d) = director()
        d.setActivity(AvatarActivity.THINKING)
        d.notifyTaskSucceeded()
        assertEquals(AvatarState.SUCCESS, d.state)
    }

    @Test
    fun thinkingBeatsListening() = runTest {
        val (_, d) = director()
        d.setUserTyping(true)
        d.setActivity(AvatarActivity.THINKING)
        assertEquals(AvatarState.THINKING, d.state)
    }

    @Test
    fun listeningBeatsIdle() = runTest {
        val (_, d) = director()
        assertEquals(AvatarState.IDLE, d.state)
        d.setUserTyping(true)
        assertEquals(AvatarState.LISTENING, d.state)
    }

    // ---- SLEEPING gating -----------------------------------------------------

    @Test
    fun sleepingGatesThinkingAndListeningAndSpeaking() = runTest {
        val (_, d) = director()
        d.setQuietHours(true)
        d.setActivity(AvatarActivity.THINKING)
        assertEquals(AvatarState.SLEEPING, d.state)
        d.setActivity(AvatarActivity.SPEAKING)
        assertEquals(AvatarState.SLEEPING, d.state)
        d.setUserTyping(true)
        assertEquals(AvatarState.SLEEPING, d.state)
        d.setAwaitingApproval(true)
        assertEquals(AvatarState.SLEEPING, d.state)
    }

    @Test
    fun draggedBypassesSleeping() = runTest {
        val (_, d) = director()
        d.setQuietHours(true)
        d.setDragged(true)
        assertEquals(AvatarState.DRAGGED, d.state)
    }

    @Test
    fun errorBypassesSleeping() = runTest {
        val (_, d) = director()
        d.setQuietHours(true)
        d.notifyError()
        assertEquals(AvatarState.ERROR, d.state)
    }

    @Test
    fun legacyActivityAfterErrorClearsLatchWithoutIdle() = runTest {
        // Pre-P2 setActivity contract: any subsequent activity replaces ERROR.
        // A caller that recovers with SPEAKING (never sending IDLE) must not be
        // stuck red (Codex: clear latched errors on any new activity).
        val (_, d) = director()
        d.setActivity(AvatarActivity.ERROR)
        assertEquals(AvatarState.ERROR, d.state)
        d.setActivity(AvatarActivity.SPEAKING)
        assertEquals(AvatarState.SPEAKING, d.state)

        // THINKING and LISTENING recover the same way.
        d.setActivity(AvatarActivity.ERROR)
        d.setActivity(AvatarActivity.THINKING)
        assertEquals(AvatarState.THINKING, d.state)
        d.setActivity(AvatarActivity.ERROR)
        d.setActivity(AvatarActivity.LISTENING)
        assertEquals(AvatarState.LISTENING, d.state)
    }

    // ---- SUCCESS self-expiry -------------------------------------------------

    @Test
    fun successSelfExpiresAfterItsTotal() = runTest {
        val (_, d) = director()
        d.setActivity(AvatarActivity.IDLE)
        d.notifyTaskSucceeded()
        assertEquals(AvatarState.SUCCESS, d.state)

        // successTotal = hold 1.5 + decay 0.5 = 2.0s; still SUCCESS just before.
        repeat(19) { d.tick(0.1f) } // 1.9s
        assertEquals(AvatarState.SUCCESS, d.state)
        repeat(2) { d.tick(0.1f) } // cross 2.0s
        assertEquals(AvatarState.IDLE, d.state)
    }

    @Test
    fun successReplaysHappyFlashOnNewCompletionWithinWindow() = runTest {
        val (runtime, d) = director()
        d.notifyTaskSucceeded()
        assertEquals(0.7f, runtime.expressionWeights["happy"])

        // Drive into the decay tail (past the 1.5s hold) while still inside the
        // 2.0s SUCCESS window, so the happy flash has fallen well below its 0.7
        // peak but the director hasn't self-expired yet.
        repeat(18) { d.tick(0.1f) } // 1.8s: still SUCCESS, flash decaying
        assertEquals(AvatarState.SUCCESS, d.state)
        assertTrue((runtime.expressionWeights["happy"] ?: 0f) < 0.5f, "flash should be decaying")

        // A genuinely new completion re-flashes the happy cue even though the
        // director never left SUCCESS (Codex: replay success when already active).
        d.notifyTaskSucceeded()
        assertEquals(AvatarState.SUCCESS, d.state)
        assertEquals(0.7f, runtime.expressionWeights["happy"]!!, 0.001f)
    }

    @Test
    fun successFallsBackToUnderlyingStateAfterExpiry() = runTest {
        val (_, d) = director()
        d.setActivity(AvatarActivity.THINKING)
        d.notifyTaskSucceeded()
        assertEquals(AvatarState.SUCCESS, d.state)
        repeat(21) { d.tick(0.1f) } // > 2.0s
        assertEquals(AvatarState.THINKING, d.state) // THINKING still active underneath
    }

    // ---- WAITING_INPUT steady vs THINKING pulse (presence proves the difference)

    @Test
    fun waitingIsSteadyAmberThinkingIsPulseAmber() = runTest {
        val (_, d) = director()
        d.setActivity(AvatarActivity.THINKING)
        assertEquals(
            PresenceCue(PresenceSemantics.Color.AMBER, PresenceSemantics.Mode.PULSE, 1200),
            PresenceSemantics.cueFor(d.state),
        )
        d.setAwaitingApproval(true)
        assertEquals(
            PresenceCue(PresenceSemantics.Color.AMBER, PresenceSemantics.Mode.STEADY),
            PresenceSemantics.cueFor(d.state),
        )
    }

    // ---- LISTENING enter/exit via typing with 0.8s release -------------------

    @Test
    fun listeningReleasesAfterEightHundredMillis() = runTest {
        val (_, d) = director()
        d.setUserTyping(true)
        assertEquals(AvatarState.LISTENING, d.state)

        d.setUserTyping(false) // starts the 0.8s release timer
        assertEquals(AvatarState.LISTENING, d.state) // still lingering

        repeat(7) { d.tick(0.1f) } // 0.7s
        assertEquals(AvatarState.LISTENING, d.state)
        repeat(2) { d.tick(0.1f) } // cross 0.8s
        assertEquals(AvatarState.IDLE, d.state)
    }

    @Test
    fun typingAgainDuringReleaseKeepsListening() = runTest {
        val (_, d) = director()
        d.setUserTyping(true)
        d.setUserTyping(false)
        d.tick(0.5f)
        d.setUserTyping(true) // resumes typing before release completes
        d.tick(1.0f)
        assertEquals(AvatarState.LISTENING, d.state)
    }

    // ---- lifecycle feed ------------------------------------------------------

    @Test
    fun lifecycleOverridesBehaviorStates() = runTest {
        val (_, d) = director()
        d.setActivity(AvatarActivity.SPEAKING)
        d.setLifecycle(AvatarLifecycle.LOADING)
        assertEquals(AvatarState.LOADING, d.state)
        d.setLifecycle(AvatarLifecycle.FAILED)
        assertEquals(AvatarState.FAILED, d.state)
        d.setLifecycle(AvatarLifecycle.DEGRADED)
        assertEquals(AvatarState.DEGRADED, d.state)
        // NONE lets the still-active SPEAKING behavior show through again.
        d.setLifecycle(AvatarLifecycle.NONE)
        assertEquals(AvatarState.SPEAKING, d.state)
    }

    @Test
    fun lifecycleOverridesEvenDraggedAndError() = runTest {
        val (_, d) = director()
        d.setDragged(true)
        d.notifyError()
        d.setLifecycle(AvatarLifecycle.FAILED)
        assertEquals(AvatarState.FAILED, d.state)
    }

    // ---- transition listener ordering ----------------------------------------

    @Test
    fun listenerSeesExitBeforeEnterAndCorrectPair() = runTest {
        val (_, d) = director()
        val transitions = mutableListOf<Pair<AvatarState, AvatarState>>()
        d.addStateListener { exit, enter -> transitions.add(exit to enter) }

        d.setActivity(AvatarActivity.THINKING)
        d.setActivity(AvatarActivity.SPEAKING)

        assertEquals(
            listOf(
                AvatarState.IDLE to AvatarState.THINKING,
                AvatarState.THINKING to AvatarState.SPEAKING,
            ),
            transitions,
        )
        // Each pair's exit is the prior enter — proves ordering is consistent.
        transitions.forEach { (exit, enter) -> assertNotEquals(exit, enter) }
    }

    @Test
    fun removedListenerStopsReceiving() = runTest {
        val (_, d) = director()
        var count = 0
        val listener = AvatarStateListener { _, _ -> count++ }
        d.addStateListener(listener)
        d.setActivity(AvatarActivity.THINKING)
        assertEquals(1, count)
        d.removeStateListener(listener)
        d.setActivity(AvatarActivity.SPEAKING)
        assertEquals(1, count)
    }

    @Test
    fun listenerMutatingListenersDuringCallbackDoesNotThrow() = runTest {
        val (_, d) = director()
        var secondCalled = false
        // Two listeners; the first removes the second during its callback. With
        // an index-based loop over the live list this threw IndexOutOfBounds
        // (Codex: notify listeners from a stable snapshot).
        val second = AvatarStateListener { _, _ -> secondCalled = true }
        d.addStateListener { _, _ -> d.removeStateListener(second) }
        d.addStateListener(second)

        d.setActivity(AvatarActivity.THINKING) // must not throw

        assertEquals(AvatarState.THINKING, d.state)
        // The snapshot was taken before the mutation, so the second listener
        // still saw this transition; its removal takes effect next time.
        assertTrue(secondCalled)
    }

    @Test
    fun noTransitionEmittedWhenStateUnchanged() = runTest {
        val (_, d) = director()
        var count = 0
        d.addStateListener { _, _ -> count++ }
        d.setActivity(AvatarActivity.THINKING)
        d.setActivity(AvatarActivity.THINKING) // same → no emit
        assertEquals(1, count)
    }

    // ---- §6 timing constants observed through recorded weights ---------------

    @Test
    fun thinkingRelaxedAttacksToPointSixOverPointFourSeconds() = runTest {
        val (runtime, d) = director()
        d.setActivity(AvatarActivity.THINKING)
        // Immediately after enter: ramp starts at 0.
        assertEquals(0f, runtime.expressionWeights["relaxed"])
        d.tick(0.2f) // half the 0.4s attack → ~0.3
        val mid = runtime.expressionWeights["relaxed"] ?: 0f
        assertTrue(mid in 0.25f..0.35f, "halfway attack ~0.3, was $mid")
        d.tick(0.2f) // reach full attack → 0.6
        assertEquals(0.6f, runtime.expressionWeights["relaxed"]!!, 0.001f)
    }

    @Test
    fun waitingInputSurprisedIsPointThreeImmediately() = runTest {
        val (runtime, d) = director()
        d.setAwaitingApproval(true)
        assertEquals(0.3f, runtime.expressionWeights["surprised"])
    }

    @Test
    fun errorSadHoldsPointEightThenDecays() = runTest {
        val config = fixedBlinkConfig() // errorHold 1.2 / decay 0.4 defaults
        val runtime = readyRuntime()
        val d = AvatarDirector(runtime, Random(1), config)
        d.notifyError()
        assertEquals(0.8f, runtime.expressionWeights["sad"])
        repeat(11) { d.tick(0.1f) } // 1.1s, still holding
        assertEquals(0.8f, runtime.expressionWeights["sad"])
        repeat(6) { d.tick(0.1f) } // through hold remainder + 0.4s decay
        assertEquals(0f, runtime.expressionWeights["sad"])
    }

    @Test
    fun successHappyIsPointSevenOnEnter() = runTest {
        val (runtime, d) = director()
        d.notifyTaskSucceeded()
        assertEquals(0.7f, runtime.expressionWeights["happy"])
    }

    @Test
    fun draggedSurprisedIsPointTwoImmediately() = runTest {
        val (runtime, d) = director()
        d.setDragged(true)
        assertEquals(0.2f, runtime.expressionWeights["surprised"])
    }

    // ---- SLEEPING behavior (eyes closed, blink suppressed) --------------------

    @Test
    fun sleepingHoldsEyesClosedAndSuppressesBlink() = runTest {
        val (runtime, d) = director()
        d.setQuietHours(true)
        assertEquals(1f, runtime.expressionWeights["blink"]) // eyes closed
        // Blink loop suppressed: eyes stay closed across the whole blink period.
        repeat(30) { d.tick(0.1f) } // 3s > blink interval
        assertEquals(1f, runtime.expressionWeights["blink"])
    }

    @Test
    fun leavingSleepingReleasesEyes() = runTest {
        val (runtime, d) = director()
        d.setQuietHours(true)
        assertEquals(1f, runtime.expressionWeights["blink"])
        d.setQuietHours(false)
        assertEquals(0f, runtime.expressionWeights["blink"])
    }

    // ---- expression cross-fade: exit clears old base before enter -------------

    @Test
    fun exitClearsPriorBaseExpression() = runTest {
        val (runtime, d) = director()
        d.setActivity(AvatarActivity.THINKING)
        d.tick(0.4f)
        assertTrue((runtime.expressionWeights["relaxed"] ?: 0f) > 0f)
        d.setAwaitingApproval(true) // → WAITING_INPUT
        assertEquals(0f, runtime.expressionWeights["relaxed"]) // relaxed cleared
        assertEquals(0.3f, runtime.expressionWeights["surprised"])
    }
}
