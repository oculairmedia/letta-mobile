package com.letta.mobile.feature.chat.screen

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * The FAB-band math of [composerBandAboveInput]: the visually transparent part
 * of the composer column above the measured input card. The scroll-to-bottom
 * FAB subtracts this band from the list's bottom clearance so it anchors above
 * the card the user perceives as "the composer" instead of above the whole
 * column (product feedback, 2026-09-28).
 */
@Tag("unit")
class ChatScreenLayoutStateTest {

    @Test
    fun bandIsTheColumnHeightMinusTheCardHeight() {
        assertEquals(80.dp, composerBandAboveInput(composerHeight = 200.dp, inputCardHeight = 120.dp))
    }

    @Test
    fun bandIsZeroUntilTheCardReportsItsFirstMeasurement() {
        // inputCardHeight == 0.dp means the card's onSizeChanged has not fired
        // yet. Reporting the full column height here would float the FAB a
        // whole composer above the card on frame one.
        assertEquals(0.dp, composerBandAboveInput(composerHeight = 200.dp, inputCardHeight = 0.dp))
        assertEquals(0.dp, composerBandAboveInput(composerHeight = 0.dp, inputCardHeight = 0.dp))
    }

    @Test
    fun bandIsZeroWhenTheColumnIsTheCard() {
        assertEquals(0.dp, composerBandAboveInput(composerHeight = 120.dp, inputCardHeight = 120.dp))
    }

    @Test
    fun bandIsNeverNegativeOnTransientMeasurementFrames() {
        // A frame where the card measures taller than the column (measurement
        // ordering) must fall back to zero, never pull the FAB below the card.
        assertEquals(0.dp, composerBandAboveInput(composerHeight = 100.dp, inputCardHeight = 140.dp))
    }
}
