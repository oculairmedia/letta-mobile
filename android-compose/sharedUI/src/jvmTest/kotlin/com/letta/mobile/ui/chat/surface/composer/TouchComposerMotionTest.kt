package com.letta.mobile.ui.chat.surface.composer

import com.letta.mobile.ui.theme.TouchComposerDimens
import kotlin.test.Test
import kotlin.test.assertEquals
import androidx.compose.ui.unit.dp

/** letta-mobile-bglj6.1.19: the Touch composer's motion decisions. */
class TouchComposerMotionTest {
    @Test
    fun aLeavingMicStaysTheMicThroughItsExit() {
        // Typing the first character with the keyboard up hides the slot and ends dictation in
        // the same frame: the exit must keep drawing the mic, not flash Send.
        val shown = retainedTrailingContent(
            visible = false,
            current = TouchTrailingContent.Action,
            retained = TouchTrailingContent.Voice,
        )
        assertEquals(TouchTrailingContent.Voice, shown)
    }

    @Test
    fun aVisibleSlotShowsWhatItHoldsNow() {
        val shown = retainedTrailingContent(
            visible = true,
            current = TouchTrailingContent.Action,
            retained = TouchTrailingContent.Voice,
        )
        assertEquals(TouchTrailingContent.Action, shown)
    }

    @Test
    fun aPressedSheetRowRoundsAndLiftsAsTheLegacyRowDoes() {
        assertEquals(SheetRowLook(corner = 8.dp, elevation = 2.dp), sheetRowLook(pressed = false))
        assertEquals(SheetRowLook(corner = 12.dp, elevation = 4.dp), sheetRowLook(pressed = true))
        assertEquals(TouchComposerDimens.sheetItemCorner, sheetRowLook(pressed = false).corner)
    }
}
