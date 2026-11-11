package com.letta.mobile.data.voice.activation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class ActivationTest {
    private val Int.atMs: KeyTime get() = KeyTime(milliseconds)

    private fun decisions(mode: ActivationKeyMode, vararg gestures: KeyGesture): List<KeyDecision> {
        val classifier = ActivationKeyClassifier(mode)
        var state = KeyClassifierState()
        return gestures.map { gesture -> classifier.classify(state, gesture).also { state = it.state }.decision }
    }

    @Test
    fun autoTapTogglesAndHoldIsPushToTalk() {
        val tap = decisions(ActivationKeyMode.AUTO, KeyGesture.Press(0.atMs), KeyGesture.Release(120.atMs))
        assertEquals(listOf(KeyDecision.START, KeyDecision.NONE), tap)
        val toggleOff = decisions(
            ActivationKeyMode.AUTO,
            KeyGesture.Press(0.atMs), KeyGesture.Release(100.atMs), KeyGesture.Press(900.atMs), KeyGesture.Release(950.atMs),
        )
        assertEquals(listOf(KeyDecision.START, KeyDecision.NONE, KeyDecision.END_TURN, KeyDecision.NONE), toggleOff)
        val hold = decisions(ActivationKeyMode.AUTO, KeyGesture.Press(0.atMs), KeyGesture.Release(300.atMs))
        assertEquals(listOf(KeyDecision.START, KeyDecision.END_TURN), hold)
    }

    @Test
    fun holdModeEndsOnEveryRelease() {
        val gestures = decisions(ActivationKeyMode.HOLD, KeyGesture.Press(0.atMs), KeyGesture.Release(50.atMs))
        assertEquals(listOf(KeyDecision.START, KeyDecision.END_TURN), gestures)
    }

    @Test
    fun toggleModeIgnoresReleases() {
        val gestures = decisions(
            ActivationKeyMode.TOGGLE,
            KeyGesture.Press(0.atMs), KeyGesture.Release(2000.atMs), KeyGesture.Press(3000.atMs),
        )
        assertEquals(listOf(KeyDecision.START, KeyDecision.NONE, KeyDecision.END_TURN), gestures)
    }

    @Test
    fun cancelDiscardsOnlyWhenActive() {
        assertEquals(listOf(KeyDecision.NONE), decisions(ActivationKeyMode.AUTO, KeyGesture.Cancel))
        val discarded = decisions(ActivationKeyMode.HOLD, KeyGesture.Press(0.atMs), KeyGesture.Cancel, KeyGesture.Release(10.atMs))
        assertEquals(listOf(KeyDecision.START, KeyDecision.DISCARD, KeyDecision.NONE), discarded)
    }
}
