package com.letta.mobile.data.desktopshell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellLayoutReducerTest {
    @Test
    fun toggleFlipsCollapsedAndRecordsExplicitPreference() {
        val initial = ShellLayoutState()
        assertFalse(initial.collapsedPreference)
        assertFalse(initial.hasExplicitPreference)

        val collapsed = ShellLayoutReducer.reduce(initial, ShellLayoutEvent.ToggleSidebar)
        assertTrue(collapsed.collapsedPreference)
        assertTrue(collapsed.hasExplicitPreference)

        val expanded = ShellLayoutReducer.reduce(collapsed, ShellLayoutEvent.ToggleSidebar)
        assertFalse(expanded.collapsedPreference)
        assertTrue(expanded.hasExplicitPreference)
    }

    @Test
    fun setSidebarCollapsedIsExplicitRegardlessOfPriorState() {
        val initial = ShellLayoutState()
        val forced = ShellLayoutReducer.reduce(initial, ShellLayoutEvent.SetSidebarCollapsed(true))
        assertTrue(forced.collapsedPreference)
        assertTrue(forced.hasExplicitPreference)

        val reopened = ShellLayoutReducer.reduce(forced, ShellLayoutEvent.SetSidebarCollapsed(false))
        assertFalse(reopened.collapsedPreference)
    }

    @Test
    fun resizeClampsToMinMaxAndMarksExplicit() {
        val initial = ShellLayoutState()

        val tooNarrow = ShellLayoutReducer.reduce(initial, ShellLayoutEvent.ResizeSidebar(50f))
        assertEquals(ShellLayoutReducer.MIN_SIDEBAR_WIDTH_DP, tooNarrow.sidebarWidthDp)

        val tooWide = ShellLayoutReducer.reduce(initial, ShellLayoutEvent.ResizeSidebar(900f))
        assertEquals(ShellLayoutReducer.MAX_SIDEBAR_WIDTH_DP, tooWide.sidebarWidthDp)

        val inRange = ShellLayoutReducer.reduce(initial, ShellLayoutEvent.ResizeSidebar(280f))
        assertEquals(280f, inRange.sidebarWidthDp)
        assertTrue(inRange.hasExplicitPreference)
    }

    @Test
    fun widthChangeDefaultsCollapsedBelowBreakpointUntilExplicitChoiceIsMade() {
        val initial = ShellLayoutState()
        assertFalse(initial.hasExplicitPreference)

        val narrow = ShellLayoutReducer.reduce(
            initial,
            ShellLayoutEvent.WindowWidthChanged(ShellLayoutReducer.SIDEBAR_COLLAPSE_BREAKPOINT_DP - 1f),
        )
        assertTrue(narrow.collapsedPreference)
        assertFalse(narrow.hasExplicitPreference)
        assertTrue(narrow.isNarrowWindow)
        assertFalse(narrow.isSidebarVisible)

        val wideAgain = ShellLayoutReducer.reduce(
            narrow,
            ShellLayoutEvent.WindowWidthChanged(ShellLayoutReducer.SIDEBAR_COLLAPSE_BREAKPOINT_DP + 400f),
        )
        assertFalse(wideAgain.collapsedPreference)
        assertTrue(wideAgain.isSidebarVisible)
    }

    @Test
    fun explicitPreferenceSurvivesWindowWidening() {
        // A user who collapses the sidebar on a wide window keeps it collapsed
        // even as the window is resized — narrow-width auto-collapse must not
        // be confused with an intentional choice, but an intentional choice
        // must not be overridden by width changes either.
        val wideState = ShellLayoutState(windowWidthDp = 1200f)
        val userCollapsed = ShellLayoutReducer.reduce(wideState, ShellLayoutEvent.ToggleSidebar)
        assertTrue(userCollapsed.collapsedPreference)

        val stillWide = ShellLayoutReducer.reduce(
            userCollapsed,
            ShellLayoutEvent.WindowWidthChanged(1400f),
        )
        assertTrue(stillWide.collapsedPreference)
        assertTrue(stillWide.hasExplicitPreference)
    }

    @Test
    fun explicitExpandedPreferenceSurvivesNarrowingBelowBreakpointStateFlagButVisibilityStillHidesAtNarrowWidth() {
        // Explicit "keep expanded" preference is preserved in state even when
        // the window narrows — but effective visibility still respects the
        // width floor (AC #5: narrow widths never crush chat).
        val wideState = ShellLayoutState(windowWidthDp = 1200f, collapsedPreference = true, hasExplicitPreference = true)
        val userExpanded = ShellLayoutReducer.reduce(wideState, ShellLayoutEvent.ToggleSidebar)
        assertFalse(userExpanded.collapsedPreference)

        val narrowed = ShellLayoutReducer.reduce(
            userExpanded,
            ShellLayoutEvent.WindowWidthChanged(400f),
        )
        assertFalse(narrowed.collapsedPreference)
        assertFalse(narrowed.isSidebarVisible)
    }

    @Test
    fun defaultCollapsedForWidthMatchesBreakpoint() {
        assertTrue(ShellLayoutReducer.defaultCollapsedForWidth(839f))
        assertFalse(ShellLayoutReducer.defaultCollapsedForWidth(840f))
        assertFalse(ShellLayoutReducer.defaultCollapsedForWidth(1200f))
    }
}
