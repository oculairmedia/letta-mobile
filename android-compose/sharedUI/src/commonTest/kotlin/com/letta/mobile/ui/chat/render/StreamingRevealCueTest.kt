package com.letta.mobile.ui.chat.render

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.1.18: the reveal-pulse gate, as the legacy Android chat gated it
 * (feature-chat StreamingDisplayTextTest's contract): a reveal pulses on a chunk of new
 * characters or at a word/sentence boundary, never on a shrink and never on plain mid-word
 * letter growth.
 */
class StreamingRevealCueTest {
    @Test
    fun aChunkOfNewCharactersPulses() {
        assertTrue(shouldPulseForStreamingReveal(0, "a".repeat(STREAMING_REVEAL_CUE_MIN_CHARS + 1)))
        assertTrue(shouldPulseForStreamingReveal(4, "a".repeat(4 + STREAMING_REVEAL_CUE_MIN_CHARS)))
    }

    @Test
    fun aBoundaryCharacterPulses() {
        assertTrue(shouldPulseForStreamingReveal(5, "Hello "), "a trailing space is a word boundary")
        assertTrue(shouldPulseForStreamingReveal(11, "Hello there."), "a trailing sentence stop is a boundary")
        for (boundary in listOf(',', ';', ':', '!', '?', ')', ']', '}')) {
            assertTrue(shouldPulseForStreamingReveal(3, "abc$boundary"), "boundary $boundary should pulse")
        }
    }

    @Test
    fun midWordLetterGrowthDoesNotPulse() {
        assertFalse(shouldPulseForStreamingReveal(2, "abc"))
        assertFalse(shouldPulseForStreamingReveal(0, "a"))
    }

    @Test
    fun aShrinkNeverPulses() {
        assertFalse(shouldPulseForStreamingReveal(10, "short"))
        assertFalse(shouldPulseForStreamingReveal(3, "abc"))
    }
}