package com.letta.mobile.ui.canvas.boards

import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.canvas.library.CanvasBoardSessions
import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The board a page shows keeps one hold on what it opened through the window's table, and gives it
 * back when the page moves on, without touching a board it shows from elsewhere or a module's hold.
 */
class CanvasPageBoardTest {
    private val notes = CanvasId("canvas-notes")
    private val plan = CanvasId("canvas-plan")

    private suspend fun boards(): CanvasBoardSessions {
        val store = InMemoryCanvasDocumentStore()
        listOf(notes, plan).forEach { id -> CanvasSession.create(store, CanvasCreateOptions(canvasId = id)) }
        return CanvasBoardSessions(store)
    }

    @Test
    fun aTakenBoardIsShownAndHeld() = runTest {
        val boards = boards()
        val page = CanvasPageBoard(boards)
        val session = assertNotNull(boards.acquire(notes))

        page.take(session)

        assertSame(session, page.session)
        assertEquals(1, boards.holds(notes))
    }

    @Test
    fun showingAnotherBoardGivesBackThePageHold() = runTest {
        val boards = boards()
        val page = CanvasPageBoard(boards)
        page.take(assertNotNull(boards.acquire(notes)))

        page.take(assertNotNull(boards.acquire(plan)))
        assertEquals(0, boards.holds(notes))
        assertEquals(1, boards.holds(plan))

        page.session = null
        assertNull(page.session)
        assertEquals(0, boards.holds(plan))
    }

    @Test
    fun reopeningTheShownBoardKeepsOneHold() = runTest {
        val boards = boards()
        val page = CanvasPageBoard(boards)
        page.take(assertNotNull(boards.acquire(notes)))

        page.take(assertNotNull(boards.acquire(notes)))

        assertEquals(1, boards.holds(notes))
    }

    @Test
    fun closingThePageLeavesAModuleHoldInPlace() = runTest {
        val boards = boards()
        val page = CanvasPageBoard(boards)
        val moduleSession = assertNotNull(boards.acquire(notes))
        page.take(assertNotNull(boards.acquire(notes)))

        page.session = null

        assertEquals(1, boards.holds(notes))
        assertSame(moduleSession, boards.acquire(notes))
    }

    @Test
    fun aBoardShownFromElsewhereIsNotGivenBack() = runTest {
        val boards = boards()
        val page = CanvasPageBoard(boards)
        val moduleSession = assertNotNull(boards.acquire(notes))

        page.session = moduleSession
        page.session = null

        assertEquals(1, boards.holds(notes), "the page never held it, so the module's hold stays")
    }
}
