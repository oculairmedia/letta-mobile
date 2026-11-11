package com.letta.mobile.data.canvas.library

import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
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
    fun hostCreationAndModuleOpenShareOneHoldTable() = runTest {
        val store = storeWithNotes()
        val boards = CanvasBoardSessions(store)
        val page = boards.acquirePage(notes, null, null)
        val module = assertNotNull(boards.acquire(notes))
        assertSame(page, module)
        assertSame(page, boards.acquirePage(notes, null, null))
        boards.release(page)
        boards.release(module)
        boards.release(page)
        assertEquals(0, boards.holds(notes))
        assertNotSame(page, boards.acquire(notes))
    }

    @Test
    fun aPageUsesTheModuleSessionAndReleasesItsOwnHold() = runTest {
        val boards = CanvasBoardSessions(storeWithNotes())
        val module = assertNotNull(boards.acquire(notes))
        val page = boards.acquirePage(notes, null, null)
        assertSame(module, page)
        boards.release(page)
        assertEquals(1, boards.holds(notes))
        module.updateTitle("Still mounted")
        assertEquals("Still mounted", page.document.value?.title)
        boards.release(module)
        assertEquals(0, boards.holds(notes))
    }

    @Test
    fun simultaneousConversationPagesCreateOnlyOneSession() = runTest {
        val boards = CanvasBoardSessions(InMemoryCanvasDocumentStore())
        val conversation = com.letta.mobile.data.model.ConversationId("board-owner-conversation")
        val first = async { boards.acquirePage(null, conversation, null) }
        val second = async { boards.acquirePage(null, conversation, null) }
        val page = first.await()
        assertSame(page, second.await())
        assertSame(page, boards.acquire(page.canvasId))
        repeat(3) { boards.release(page) }
        assertEquals(0, boards.holds(page.canvasId))
    }

    @Test
    fun theOwnedRegistryAdoptsAnOutsideMount() = runTest {
        val store = storeWithNotes()
        val boards = CanvasBoardSessions(store)
        val mounted = assertNotNull(CanvasSession.open(store, notes))
        val dispose = boards.registry.registerMount(mounted)
        assertSame(mounted, boards.acquirePage(notes, null, null))
        boards.release(mounted)
        dispose()
        assertNull(boards.registry.get(notes))
        assertEquals(0, boards.holds(notes))
    }

    @Test
    fun retiringAnOwnerForgetsHoldsAndMountsAndIgnoresTheirLateDisposers() = runTest {
        val boards = CanvasBoardSessions(storeWithNotes())
        val page = assertNotNull(boards.acquire(notes))
        boards.acquire(notes)
        val dispose = boards.registry.registerMount(page)
        boards.clear()
        assertEquals(0, boards.holds(notes))
        assertNull(boards.registry.get(notes))
        boards.release(page)
        dispose()
        assertEquals(0, boards.holds(notes))
        assertNull(boards.registry.get(notes))
        assertNotSame(page, boards.acquire(notes))
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

    @Test
    fun aLibraryOpenCancelledBeforeThePageTakesItGivesTheHoldBack() = runTest {
        val backing = storeWithNotes()
        val owner = CoroutineScope(StandardTestDispatcher(testScheduler))
        // The library's owner goes away while the board is being read, so the page never takes it.
        val store = object : CanvasDocumentStore by backing {
            override suspend fun get(id: CanvasId): CanvasDocument? = backing.get(id).also { owner.cancel() }
        }
        val boards = CanvasBoardSessions(store)
        var page: CanvasSession? = null

        CanvasLibraryController(store, owner).open(notes, boards) { page = it }
        runCurrent()

        assertNull(page)
        assertEquals(0, boards.holds(notes), "a hold nobody received is given back")
    }
}
