package com.letta.mobile.data.voice.activation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class ActivationTest {
    private fun at(millis: Int) = KeyTime(millis.milliseconds)

    private fun decisions(mode: ActivationKeyMode, vararg gestures: KeyGesture): List<KeyDecision> {
        val classifier = ActivationKeyClassifier(mode)
        var state = KeyClassifierState()
        return gestures.map { gesture -> classifier.classify(state, gesture).also { state = it.state }.decision }
    }

    @Test
    fun autoTapTogglesAndHoldIsPushToTalk() {
        val tap = decisions(ActivationKeyMode.AUTO, KeyGesture.Press(at(0)), KeyGesture.Release(at(120)))
        assertEquals(listOf(KeyDecision.START, KeyDecision.NONE), tap)
        val toggleOff = decisions(
            ActivationKeyMode.AUTO,
            KeyGesture.Press(at(0)), KeyGesture.Release(at(100)), KeyGesture.Press(at(900)), KeyGesture.Release(at(950)),
        )
        assertEquals(listOf(KeyDecision.START, KeyDecision.NONE, KeyDecision.END_TURN, KeyDecision.NONE), toggleOff)
        val hold = decisions(ActivationKeyMode.AUTO, KeyGesture.Press(at(0)), KeyGesture.Release(at(300)))
        assertEquals(listOf(KeyDecision.START, KeyDecision.END_TURN), hold)
    }

    @Test
    fun holdModeEndsOnEveryRelease() {
        val gestures = decisions(ActivationKeyMode.HOLD, KeyGesture.Press(at(0)), KeyGesture.Release(at(50)))
        assertEquals(listOf(KeyDecision.START, KeyDecision.END_TURN), gestures)
    }

    @Test
    fun toggleModeIgnoresReleases() {
        val gestures = decisions(
            ActivationKeyMode.TOGGLE,
            KeyGesture.Press(at(0)), KeyGesture.Release(at(2000)), KeyGesture.Press(at(3000)),
        )
        assertEquals(listOf(KeyDecision.START, KeyDecision.NONE, KeyDecision.END_TURN), gestures)
    }

    @Test
    fun cancelDiscardsOnlyWhenActive() {
        assertEquals(listOf(KeyDecision.NONE), decisions(ActivationKeyMode.AUTO, KeyGesture.Cancel))
        val discarded = decisions(ActivationKeyMode.HOLD, KeyGesture.Press(at(0)), KeyGesture.Cancel, KeyGesture.Release(at(10)))
        assertEquals(listOf(KeyDecision.START, KeyDecision.DISCARD, KeyDecision.NONE), discarded)
    }
}
