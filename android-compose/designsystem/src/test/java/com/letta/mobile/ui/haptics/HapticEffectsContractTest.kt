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
}
