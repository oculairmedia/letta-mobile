package com.letta.mobile.data.canvas.library

import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.InMemoryCanvasArchiveStore
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasLibraryControllerTest {
    @Test
    fun failedRefreshRetainsBoardsAndArchiveThenRetryRecovers() = runTest {
        val board = CanvasDocument(id = CanvasId("retained"), title = "Keep me")
        val backing = InMemoryCanvasDocumentStore()
        backing.upsert(board)
        var fail = false
        val store = object : CanvasDocumentStore by backing {
            override suspend fun listAll(): List<CanvasDocument> {
                check(!fail) { "Storage timed out" }
                return backing.listAll()
            }
        }
        val archive = InMemoryCanvasArchiveStore()
        archive.setArchived(board.id, true)
        val library = CanvasLibraryController(store, backgroundScope, archive)
        library.refresh()
        runCurrent()
        fail = true
        library.refresh()
        runCurrent()
        assertEquals(listOf(board), library.state.value.documents)
        assertEquals(setOf(board.id), library.state.value.archived)
        assertNotNull(library.state.value.error)
        assertFalse(library.state.value.isLoading)
        var homeStillRuns = false
        backgroundScope.launch { homeStillRuns = true }
        runCurrent()
        assertTrue(homeStillRuns)
        fail = false
        library.refresh()
        runCurrent()
        assertNull(library.state.value.error)
        assertEquals(listOf(board), library.state.value.documents)
    }

    @Test
    fun concurrentRefreshesShareOneStorageRead() = runTest {
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val store = object : CanvasDocumentStore by InMemoryCanvasDocumentStore() {
            override suspend fun listAll(): List<CanvasDocument> {
                reads++
                gate.await()
                return emptyList()
            }
        }
        val library = CanvasLibraryController(store, backgroundScope)
        library.refresh()
        runCurrent()
        repeat(5) { library.refresh() }
        runCurrent()
        assertEquals(1, reads)
        assertTrue(library.state.value.isLoading)
        gate.complete(Unit)
        runCurrent()
        assertFalse(library.state.value.isLoading)
    }

    @Test
    fun cancellationDoesNotBecomeAStorageError() = runTest {
        val store = object : CanvasDocumentStore by InMemoryCanvasDocumentStore() {
            override suspend fun listAll(): List<CanvasDocument> {
                throw kotlinx.coroutines.CancellationException("Window closed")
            }
        }
        val library = CanvasLibraryController(store, backgroundScope)
        library.refresh()
        runCurrent()
        assertNull(library.state.value.error)
        assertFalse(library.state.value.isLoading)
    }

    @Test
    fun failedOpenDoesNotInvokeNavigationAndRetryOnlyReads() = runTest {
        var writes = 0
        val store = object : CanvasDocumentStore by InMemoryCanvasDocumentStore() {
            override suspend fun get(id: CanvasId): CanvasDocument? = error("Lookup timed out")
            override suspend fun upsert(doc: CanvasDocument) { writes++ }
        }
        val library = CanvasLibraryController(store, backgroundScope)
        var navigated = false
        library.open(CanvasId("unavailable")) { navigated = true }
        runCurrent()
        assertFalse(navigated)
        assertNotNull(library.state.value.error)
        library.refresh()
        runCurrent()
        assertEquals(0, writes)
        assertNull(library.state.value.error)
    }
}
