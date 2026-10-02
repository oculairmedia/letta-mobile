package com.letta.mobile.ui.text

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbarStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

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
        assertEquals(listOf("Copy", "Select all"), toolbar.shown?.offers?.map { it.label })

        toolbar.showMenu(Rect.Zero, onCopyRequested = {}, onPasteRequested = {}, onCutRequested = {}, onSelectAllRequested = {})
        assertEquals(listOf("Cut", "Copy", "Paste", "Select all"), toolbar.shown?.offers?.map { it.label })

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

    @Test
    fun askingAgainForTheSameSelectionChangesNothingTheBarShows() {
        // The selection asks on every update with fresh callbacks; the bar must not be re-shown.
        val toolbar = TouchTextToolbar()
        val rect = Rect(10f, 20f, 110f, 40f)
        toolbar.showMenu(rect, onCopyRequested = {}, onPasteRequested = null, onCutRequested = null, onSelectAllRequested = {})
        val first = toolbar.shown
        var copied = 0
        toolbar.showMenu(rect, onCopyRequested = { copied++ }, onPasteRequested = null, onCutRequested = null, onSelectAllRequested = {})
        assertSame(first, toolbar.shown, "the same selection re-showed the bar")
        // The newest callback still runs.
        toolbar.perform(TouchTextToolbar.Action.COPY)
        assertEquals(1, copied)
    }

    @Test
    fun aShownBarStaysThroughUpdatesAfterTheFingerIsLongGone() {
        var touch = true
        val toolbar = TouchTextToolbar { touch }
        toolbar.showMenu(Rect.Zero, onCopyRequested = {}, onPasteRequested = null, onCutRequested = null, onSelectAllRequested = null)
        touch = false
        // A reply streams in and moves the selection: the bar follows it instead of vanishing.
        toolbar.showMenu(Rect(0f, 40f, 10f, 50f), onCopyRequested = {}, onPasteRequested = null, onCutRequested = null, onSelectAllRequested = null)
        assertEquals(Rect(0f, 40f, 10f, 50f), toolbar.shown?.rect)
    }
}
