package com.letta.mobile.ui.haptics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LettaHapticCueCommonTest {
    @Test
    fun `standard platform cues do not require common pattern playback`() {
        assertEquals(LettaHapticPlayback.PlatformFeedback, LettaHapticCue.Confirm.playback)
        assertEquals(LettaHapticPlayback.PlatformFeedback, LettaHapticCue.ReorderSwapTick.playback)
        assertNull(LettaHapticPatterns.patternFor(LettaHapticCue.Confirm))
        assertNull(LettaHapticPatterns.patternFor(LettaHapticCue.ReorderDragStart))
    }

    @Test
    fun `expressive cues expose compact bounded patterns`() {
        val expressiveCues = LettaHapticCue.entries.filter { it.playback == LettaHapticPlayback.Pattern }

        expressiveCues.forEach { cue ->
            val pattern = assertNotNull(LettaHapticPatterns.patternFor(cue), "$cue should have a pattern")
            assertTrue(pattern.pulses.isNotEmpty(), "$cue should contain at least one pulse")
            assertTrue(pattern.totalDurationMs <= 150L, "$cue should stay short enough for UI feedback")
            assertEquals(pattern.pulses.sortedBy { it.startTimeMs }, pattern.pulses)
            pattern.pulses.forEach { pulse ->
                assertTrue(pulse.durationMs in 1L..80L, "$cue pulse duration should be bounded")
                assertTrue(pulse.intensity.value in 0f..1f, "$cue pulse intensity should be normalized")
            }
        }
    }

    @Test
    fun `streaming pulse is intentionally quieter than completion`() {
        val pulse = assertNotNull(LettaHapticPatterns.patternFor(LettaHapticCue.StreamingPulse))
        val complete = assertNotNull(LettaHapticPatterns.patternFor(LettaHapticCue.StreamingComplete))

        assertEquals(listOf(LettaHapticIntensity.Light), pulse.pulses.map { it.intensity })
        assertTrue(complete.pulses.any { it.intensity == LettaHapticIntensity.Strong })
    }
}
