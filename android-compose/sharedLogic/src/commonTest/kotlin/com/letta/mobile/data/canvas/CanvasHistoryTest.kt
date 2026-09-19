package com.letta.mobile.data.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The board's undo has to walk what the person did, in the order they did it, across two things
 * that each kept their own history: the drawing (DrawBox's, and private) and the documents (ops on
 * the session, which had no history at all). These tests are about that ORDER, because it is the
 * one thing neither side can know on its own.
 */
class CanvasHistoryTest {

    private fun documents(label: String) = CanvasHistory.Step.Documents(
        undo = emptyList(),
        redo = emptyList(),
        label = label,
    )

    @Test
    fun undoWalksBackThroughBothKindsInTheOrderTheyHappened() {
        val history = CanvasHistory()
        history.record(CanvasHistory.Step.Drawing)
        history.record(documents("note added"))
        history.record(CanvasHistory.Step.Drawing)

        assertEquals(CanvasHistory.Step.Drawing, history.undo())
        assertEquals("note added", (history.undo() as CanvasHistory.Step.Documents).label)
        assertEquals(CanvasHistory.Step.Drawing, history.undo())
        assertNull(history.undo())
    }

    @Test
    fun redoReplaysWhatWasUndone() {
        val history = CanvasHistory()
        history.record(documents("note added"))
        history.record(CanvasHistory.Step.Drawing)

        history.undo()
        history.undo()
        assertEquals("note added", (history.redo() as CanvasHistory.Step.Documents).label)
        assertEquals(CanvasHistory.Step.Drawing, history.redo())
        assertNull(history.redo())
    }

    @Test
    fun doingSomethingNewAbandonsTheUndoneBranch() {
        val history = CanvasHistory()
        history.record(documents("first"))
        history.undo()
        assertTrue(history.canRedo.value)

        history.record(documents("second"))

        assertFalse(history.canRedo.value)
        assertEquals("second", (history.undo() as CanvasHistory.Step.Documents).label)
        assertNull(history.undo())
    }

    @Test
    fun theFlagsFollowWhatIsActuallyThere() {
        val history = CanvasHistory()
        assertFalse(history.canUndo.value)
        assertFalse(history.canRedo.value)

        history.record(CanvasHistory.Step.Drawing)
        assertTrue(history.canUndo.value)
        assertFalse(history.canRedo.value)

        history.undo()
        assertFalse(history.canUndo.value)
        assertTrue(history.canRedo.value)
    }

    @Test
    fun theOldestStepsFallOffRatherThanGrowingForever() {
        val history = CanvasHistory(limit = 2)
        history.record(documents("first"))
        history.record(documents("second"))
        history.record(documents("third"))

        assertEquals("third", (history.undo() as CanvasHistory.Step.Documents).label)
        assertEquals("second", (history.undo() as CanvasHistory.Step.Documents).label)
        assertNull(history.undo())
    }

    @Test
    fun clearingLeavesNothingToUndoIntoABoardThatIsGone() {
        // A restored checkpoint or an agent replacing the scene means the steps that remain would
        // undo into a board that no longer exists.
        val history = CanvasHistory()
        history.record(documents("note added"))
        history.undo()

        history.clear()

        assertFalse(history.canUndo.value)
        assertFalse(history.canRedo.value)
        assertNull(history.undo())
        assertNull(history.redo())
    }
}
