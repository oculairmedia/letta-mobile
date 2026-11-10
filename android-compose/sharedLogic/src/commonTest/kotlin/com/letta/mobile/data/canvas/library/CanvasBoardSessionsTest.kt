package com.letta.mobile.data.canvas.library

import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A window's boards opened by id: one live session per stored board, kept while anyone holds it
 * and forgotten with the last hold, so a later open reads the store afresh.
 */
class CanvasBoardSessionsTest {
    private val notes = CanvasId("canvas-notes")

    private suspend fun storeWithNotes(): InMemoryCanvasDocumentStore = InMemoryCanvasDocumentStore().also { store ->
        CanvasSession.create(store, CanvasCreateOptions(title = "Notes", canvasId = notes))
    }

    @Test
    fun opensAStoredBoard() = runTest {
        val session = assertNotNull(CanvasBoardSessions(storeWithNotes()).acquire(notes))
        assertEquals(notes, session.canvasId)
        assertEquals("Notes", session.document.value?.title)
    }

    @Test
    fun anUnknownBoardIsNullAndHeldByNoOne() = runTest {
        val boards = CanvasBoardSessions(storeWithNotes())
        val missing = CanvasId("canvas-missing")
        assertNull(boards.acquire(missing))
        assertEquals(0, boards.holds(missing))
    }

    @Test
    fun theSameBoardTwiceIsTheSameSession() = runTest {
        val boards = CanvasBoardSessions(storeWithNotes())
        val first = boards.acquire(notes)
        assertSame(first, boards.acquire(notes))
        assertEquals(2, boards.holds(notes))
    }

    @Test
    fun twoAsksAtOnceShareOneOpen() = runTest {
        var reads = 0
        val backing = storeWithNotes()
        val store = object : CanvasDocumentStore by backing {
            override suspend fun get(id: CanvasId): CanvasDocument? = backing.get(id).also { reads++ }
        }
        val boards = CanvasBoardSessions(store)
        val first = async { boards.acquire(notes) }
        val second = async { boards.acquire(notes) }
        assertSame(first.await(), second.await())
        assertEquals(1, reads, "the store is read for one open only")
    }

    @Test
    fun theSessionLivesUntilTheLastHoldIsGivenBack() = runTest {
        val boards = CanvasBoardSessions(storeWithNotes())
        val session = assertNotNull(boards.acquire(notes))
        boards.acquire(notes)

        boards.release(session)
        assertSame(session, boards.acquire(notes), "one hold remained, so the page keeps the module's session")
        boards.release(session)
        boards.release(session)
        assertEquals(0, boards.holds(notes))

        assertNotSame(session, boards.acquire(notes), "with no holds left the board is opened afresh")
    }

    @Test
    fun givingBackASessionItDoesNotHoldChangesNothing() = runTest {
        val store = storeWithNotes()
        val boards = CanvasBoardSessions(store)
        val held = assertNotNull(boards.acquire(notes))
        val other = assertNotNull(CanvasSession.open(store, notes))

        boards.release(other)
        assertEquals(1, boards.holds(notes))
        assertSame(held, boards.acquire(notes))
    }

    @Test
    fun aBoardAlreadyLiveInTheWindowIsAdopted() = runTest {
        val store = storeWithNotes()
        val live = CanvasSessionRegistry()
        val mounted = assertNotNull(CanvasSession.open(store, notes))
        val disposeMount = live.registerMount(mounted)
        val boards = CanvasBoardSessions(store, live = live)

        assertSame(mounted, boards.acquire(notes))
        disposeMount()
        assertSame(mounted, boards.acquire(notes), "held, so it outlives the mount that made it live")
    }

    @Test
    fun aStoreFailurePropagatesAndHoldsNothing() = runTest {
        val backing = storeWithNotes()
        val store = object : CanvasDocumentStore by backing {
            override suspend fun get(id: CanvasId): CanvasDocument? = error("Storage unavailable")
        }
        val boards = CanvasBoardSessions(store)
        assertFailsWith<IllegalStateException> { boards.acquire(notes) }
        assertEquals(0, boards.holds(notes))
    }

    @Test
    fun theLibraryPageOpensThroughTheSameTable() = runTest {
        val store = storeWithNotes()
        val boards = CanvasBoardSessions(store)
        val module = boards.acquire(notes)
        var page: CanvasSession? = null

        CanvasLibraryController(store, backgroundScope).open(notes, boards) { page = it }
        runCurrent()

        assertSame(module, page, "the page shows the module's session, not a second copy")
        assertEquals(2, boards.holds(notes))
    }
}
