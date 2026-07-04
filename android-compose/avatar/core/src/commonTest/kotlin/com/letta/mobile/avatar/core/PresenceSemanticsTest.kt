package com.letta.mobile.avatar.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PRD §4 P3: exhaustive presence mapping over all 12 [AvatarState] values. */
class PresenceSemanticsTest {
    @Test
    fun thinkingIsAmberPulse() {
        val cue = PresenceSemantics.cueFor(AvatarState.THINKING)
        assertEquals(PresenceSemantics.Color.AMBER, cue.color)
        assertEquals(PresenceSemantics.Mode.PULSE, cue.mode)
        assertEquals(1200, cue.durationMillis)
    }

    @Test
    fun waitingInputIsAmberSteady() {
        val cue = PresenceSemantics.cueFor(AvatarState.WAITING_INPUT)
        assertEquals(PresenceSemantics.Color.AMBER, cue.color)
        assertEquals(PresenceSemantics.Mode.STEADY, cue.mode)
    }

    @Test
    fun speakingIsGreenSteady() {
        val cue = PresenceSemantics.cueFor(AvatarState.SPEAKING)
        assertEquals(PresenceSemantics.Color.GREEN, cue.color)
        assertEquals(PresenceSemantics.Mode.STEADY, cue.mode)
    }

    @Test
    fun successIsGreenFlashPointSix() {
        val cue = PresenceSemantics.cueFor(AvatarState.SUCCESS)
        assertEquals(PresenceSemantics.Color.GREEN, cue.color)
        assertEquals(PresenceSemantics.Mode.FLASH, cue.mode)
        assertEquals(600, cue.durationMillis)
    }

    @Test
    fun errorIsRedSteady() {
        val cue = PresenceSemantics.cueFor(AvatarState.ERROR)
        assertEquals(PresenceSemantics.Color.RED, cue.color)
        assertEquals(PresenceSemantics.Mode.STEADY, cue.mode)
    }

    @Test
    fun ringOffStatesAreNone() {
        listOf(
            AvatarState.IDLE,
            AvatarState.LISTENING,
            AvatarState.DRAGGED,
            AvatarState.SLEEPING,
            AvatarState.LOADING,
            AvatarState.FAILED,
            AvatarState.DEGRADED,
        ).forEach { state ->
            val cue = PresenceSemantics.cueFor(state)
            assertEquals(PresenceSemantics.Mode.NONE, cue.mode, "$state should show no ring")
            assertFalse(cue.isVisible, "$state should not be visible")
            assertEquals(PresenceCue.OFF, cue)
        }
    }

    @Test
    fun everyStateHasACueAndVisibleOnesCarryColor() {
        AvatarState.entries.forEach { state ->
            val cue = PresenceSemantics.cueFor(state)
            if (cue.isVisible) {
                assertTrue(cue.color != 0L, "$state visible cue must carry a color")
            }
        }
    }

    @Test
    fun canonicalHexTokensMatchThinkingRingPalette() {
        assertEquals(0xFFE0A33E, PresenceSemantics.Color.AMBER)
        assertEquals(0xFF34C759, PresenceSemantics.Color.GREEN)
        assertEquals(0xFFE5484D, PresenceSemantics.Color.RED)
    }

    @Test
    fun transitionDurationIsTwoFiftyMillis() {
        assertEquals(250, PresenceSemantics.TRANSITION_MILLIS)
    }
}
