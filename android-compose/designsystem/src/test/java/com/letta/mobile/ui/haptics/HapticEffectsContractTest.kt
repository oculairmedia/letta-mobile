package com.letta.mobile.ui.haptics

import android.view.HapticFeedbackConstants
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class HapticEffectsContractTest {
    @Test
    fun `standard confirm cue preserves platform-first mapping`() {
        val spec = HapticEffects.platformSpecFor(LettaHapticCue.Confirm)

        assertEquals(HapticFeedbackConstants.CONFIRM, spec?.modernPlatformType)
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, spec?.fallbackPlatformType)
        assertEquals(HapticFeedbackType.Confirm, spec?.composeType)
    }

    @Test
    fun `reorder cues keep high-frequency drag feedback on view constants`() {
        val swap = HapticEffects.platformSpecFor(LettaHapticCue.ReorderSwapTick)
        val start = HapticEffects.platformSpecFor(LettaHapticCue.ReorderDragStart)
        val end = HapticEffects.platformSpecFor(LettaHapticCue.ReorderDragEnd)

        assertEquals(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK, swap?.modernPlatformType)
        assertEquals(HapticFeedbackConstants.GESTURE_START, start?.modernPlatformType)
        assertEquals(HapticFeedbackConstants.GESTURE_END, end?.modernPlatformType)
    }

    @Test
    fun `expressive pattern cues do not claim platform haptic constants`() {
        assertNull(HapticEffects.platformSpecFor(LettaHapticCue.StreamingStart))
        assertNull(HapticEffects.platformSpecFor(LettaHapticCue.ToolCallFailed))
    }

    @Test
    fun `chat product-feel cues map to platform constants`() {
        // letta-mobile-bglj6.1.17: the send flight launches like a confirm and lands as a light
        // tick; an approval that needs a decision draws attention; the scroll glide ticks.
        assertEquals(HapticFeedbackConstants.CONFIRM, HapticEffects.platformSpecFor(LettaHapticCue.SendLaunch)?.modernPlatformType)
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, HapticEffects.platformSpecFor(LettaHapticCue.SendLand)?.modernPlatformType)
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, HapticEffects.platformSpecFor(LettaHapticCue.ApprovalNeeded)?.fallbackPlatformType)
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, HapticEffects.platformSpecFor(LettaHapticCue.ScrollToLatest)?.modernPlatformType)
    }
}
