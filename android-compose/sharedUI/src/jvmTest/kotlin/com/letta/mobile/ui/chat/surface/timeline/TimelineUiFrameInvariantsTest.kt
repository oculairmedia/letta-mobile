package com.letta.mobile.ui.chat.surface.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The frame invariants themselves, on hand-built frames (letta-mobile-29sxj). */
class TimelineUiFrameInvariantsTest {
    private fun frame(
        index: Int,
        step: String = "stream",
        rows: List<RowBounds> = listOf(RowBounds("m:a", 0f, 80f)),
        spinner: Boolean = false,
        atEdge: Boolean = true,
        scrolls: Int = 0,
        composed: Int = 0,
    ) = UiFrame(index, step, rows, spinner, if (atEdge) 0 else 1, 0, scrolls, composed)

    @Test
    fun aRowThatChangesHeightBeyondTheLimitIsAJump() {
        val frames = listOf(frame(0), frame(1, rows = listOf(RowBounds("m:a", 0f, 106f))))
        assertEquals(1, heightJumps(frames, maxDp = 2f).size)
        assertTrue(heightJumps(frames, maxDp = 30f).isEmpty())
    }

    @Test
    fun aRowThatOnlyAppearsIsNotAJump() {
        val frames = listOf(frame(0), frame(1, rows = listOf(RowBounds("m:a", 0f, 80f), RowBounds("m:b", 80f, 300f))))
        assertTrue(heightJumps(frames, maxDp = 2f).isEmpty())
    }

    @Test
    fun aSpinnerOverRowsIsAFlashButTheOpeningSkeletonIsNot() {
        val opening = listOf(frame(0, rows = emptyList(), spinner = true), frame(1, rows = emptyList(), spinner = true))
        assertTrue(spinnerFlashes(opening).isEmpty())
        val flash = opening + frame(2) + frame(3, spinner = true) + frame(4)
        assertEquals(listOf(3), spinnerFlashes(flash).map { it.index })
    }

    @Test
    fun aScrollCommandOnAnUnchangedNewestRowWhileFollowingIsAReset() {
        val frames = listOf(frame(0), frame(1, scrolls = 1), frame(2))
        assertEquals(listOf(1), scrollResetsWhileFollowing(frames).map { it.index })
    }

    @Test
    fun scrollingToAFreshNewestRowOrAwayFromTheEdgeIsNotAReset() {
        val fresh = listOf(frame(0), frame(1, rows = listOf(RowBounds("m:b", 0f, 80f)), scrolls = 1))
        assertTrue(scrollResetsWhileFollowing(fresh).isEmpty())
        val reading = listOf(frame(0, atEdge = false), frame(1, atEdge = false, scrolls = 1))
        assertTrue(scrollResetsWhileFollowing(reading).isEmpty())
    }

    @Test
    fun onlyTokenOnlyFramesOfTheStepCountTowardsRecomposition() {
        val newRow = listOf(RowBounds("m:b", 0f, 80f))
        val frames = listOf(
            frame(0, composed = 9),
            frame(1, rows = newRow, composed = 9),
            frame(2, rows = newRow, composed = 1),
            frame(3, rows = newRow, composed = 0),
            frame(4, step = "settle", rows = newRow, composed = 7),
        )
        assertEquals(listOf(1, 0), recompositionsPerFrame(frames, "stream"))
        assertEquals(emptyList(), recompositionsPerFrame(frames, "send"))
    }
}
