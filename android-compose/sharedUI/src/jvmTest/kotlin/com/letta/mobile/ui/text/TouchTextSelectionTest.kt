package com.letta.mobile.ui.text

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbarStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class TouchTextSelectionTest {

    @Test
    fun aQuoteIntoAnEmptyPromptLeavesRoomToTypeBelowIt() {
        assertEquals("> first line\n> second line\n\n", quoteIntoPrompt("", "first line\nsecond line"))
    }

    @Test
    fun aQuoteGoesAfterWhatIsAlreadyTyped() {
        assertEquals("Look at this\n\n> the part\n\n", quoteIntoPrompt("Look at this  ", "the part"))
    }

    @Test
    fun blankLinesStayInsideTheQuoteAndBlankSelectionsAddNothing() {
        assertEquals("> one\n>\n> two\n\n", quoteIntoPrompt("", "  one\n\ntwo  "))
        assertEquals("kept", quoteIntoPrompt("kept", "   \n "))
    }

    @Test
    fun theBarOffersOnlyWhatTheSelectionAllowsInPhoneOrder() {
        val toolbar = TouchTextToolbar()
        toolbar.showMenu(Rect.Zero, onCopyRequested = {}, onPasteRequested = null, onCutRequested = null, onSelectAllRequested = {})
        assertEquals(TextToolbarStatus.Shown, toolbar.status)
        assertEquals(listOf("Copy", "Select all"), toolbar.request?.actions()?.map { it.first })

        toolbar.showMenu(Rect.Zero, onCopyRequested = {}, onPasteRequested = {}, onCutRequested = {}, onSelectAllRequested = {})
        assertEquals(listOf("Cut", "Copy", "Paste", "Select all"), toolbar.request?.actions()?.map { it.first })

        toolbar.hide()
        assertEquals(TextToolbarStatus.Hidden, toolbar.status)
    }

    @Test
    fun theBarStaysAwayWhenThePlatformSaysTheSelectionWasNotByTouch() {
        var touch = false
        val toolbar = TouchTextToolbar { touch }
        toolbar.showMenu(Rect.Zero, onCopyRequested = {}, onPasteRequested = null, onCutRequested = null, onSelectAllRequested = null)
        assertEquals(TextToolbarStatus.Hidden, toolbar.status)
        touch = true
        toolbar.showMenu(Rect.Zero, onCopyRequested = {}, onPasteRequested = null, onCutRequested = null, onSelectAllRequested = null)
        assertEquals(TextToolbarStatus.Shown, toolbar.status)
    }
}
