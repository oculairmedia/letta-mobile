package com.letta.mobile.data.canvas.library

import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Portable policy tests: no Android lifecycle, filesystem or unconstrained storage fake. */
class CanvasPageHoldTest {
    private val board = CanvasId("page-hold-board")
    private val conversation = ConversationId("page-hold-conversation")

    @Test
    fun failedAcquireIsObservableAndRetryCanAcquireTheSamePage() = runTest {
        val store = TrackedStore()
        CanvasSession.create(store.backing, CanvasCreateOptions(canvasId = board))
        store.failReads = true
        val uncaught = mutableListOf<Throwable>()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob() +
            CoroutineExceptionHandler { _, error -> uncaught += error })
        val owner = CanvasBoardOwner { CanvasBoardSessions(store) }
        val page = CanvasPageHold(owner, scope)
        try {
            page.open(board, null, null)
            runCurrent()
            assertIs<CanvasPageState.Failed>(page.state.value)
            assertEquals(0, owner.boards.value.holds(board))
            store.failReads = false
            page.retry()
            runCurrent()
            val ready = assertIs<CanvasPageState.Ready>(page.state.value)
            assertEquals(board, ready.session.canvasId)
            assertEquals(1, owner.boards.value.holds(board))
            assertEquals(2, store.reads)
            assertTrue(uncaught.isEmpty(), "storage errors must be represented, not escape the page job")
        } finally {
            page.close()
            scope.cancel()
        }
    }

    @Test
    fun aStoredPageCannotEnterTheReplacementBackend() = runTest {
        assertSame(CanvasPageState.Closed, retirePage(board, null))
    }

    @Test
    fun aConversationPageCannotCreateItsConversationInTheReplacementBackend() = runTest {
        assertSame(CanvasPageState.Closed, retirePage(null, conversation))
    }

    @Test
    fun repeatedMountsKeepOneUntitledSessionAndCloseGivesBackItsHold() = runTest {
        val store = TrackedStore()
        val owner = CanvasBoardOwner { CanvasBoardSessions(store) }
        val page = CanvasPageHold(owner, backgroundScope)
        page.open(null, null, null)
        runCurrent()
        val first = assertIs<CanvasPageState.Ready>(page.state.value).session
        val reads = store.reads
        page.open(null, null, null)
        runCurrent()
        assertSame(first, assertIs<CanvasPageState.Ready>(page.state.value).session)
        assertEquals(reads, store.reads)
        assertEquals(1, owner.boards.value.holds(first.canvasId))
        val dispose = owner.boards.value.registry.registerMount(first)
        dispose()
        page.close()
        assertEquals(0, owner.boards.value.holds(first.canvasId))
        assertNull(owner.boards.value.registry.get(first.canvasId))
        assertSame(CanvasPageState.Closed, page.state.value)
    }

    @Test
    fun aFailedPageCannotRetryInAReplacementBackend() = runTest {
        val original = TrackedStore().apply { failReads = true }
        val replacement = TrackedStore()
        var factories = 0
        val owner = CanvasBoardOwner { CanvasBoardSessions(if (factories++ == 0) original else replacement) }
        owner.updateBackend(backend(OriginalBackend))
        val page = CanvasPageHold(owner, backgroundScope)
        page.open(board, null, null)
        runCurrent()
        assertIs<CanvasPageState.Failed>(page.state.value)
        owner.updateBackend(backend(ReplacementBackend))
        page.retry()
        page.open(board, null, null)
        runCurrent()
        assertSame(CanvasPageState.Closed, page.state.value)
        assertEquals(0, replacement.reads)
        assertEquals(0, replacement.writes)
        page.close()
    }

    @Test
    fun replacementCancelsAnAcquisitionWithoutPublishingFailureOrTouchingTheNewStore() = runTest {
        val started = CompletableDeferred<Unit>()
        val backing = InMemoryCanvasDocumentStore()
        val blocked = object : CanvasDocumentStore by backing {
            override suspend fun get(id: CanvasId): CanvasDocument? {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val replacement = TrackedStore()
        var factories = 0
        val owner = CanvasBoardOwner { CanvasBoardSessions(if (factories++ == 0) blocked else replacement) }
        owner.updateBackend(backend(OriginalBackend))
        val old = owner.boards.value
        val page = CanvasPageHold(owner, backgroundScope)
        page.open(board, null, null)
        runCurrent()
        assertTrue(started.isCompleted)
        owner.updateBackend(backend(ReplacementBackend))
        runCurrent()
        assertSame(CanvasPageState.Closed, page.state.value)
        assertEquals(0, old.holds(board))
        assertEquals(0, replacement.reads)
        assertEquals(0, replacement.writes)
        page.close()
    }

    private suspend fun TestScope.retirePage(canvasId: CanvasId?, conversationId: ConversationId?): CanvasPageState {
        val original = TrackedStore()
        val replacement = TrackedStore()
        if (canvasId != null) CanvasSession.create(original.backing, CanvasCreateOptions(canvasId = canvasId))
        var factories = 0
        val owner = CanvasBoardOwner { CanvasBoardSessions(if (factories++ == 0) original else replacement) }
        owner.updateBackend(backend(OriginalBackend))
        val oldBoards = owner.boards.value
        val page = CanvasPageHold(owner, backgroundScope)
        page.open(canvasId, conversationId, null)
        runCurrent()
        val session = assertIs<CanvasPageState.Ready>(page.state.value).session
        val dispose = oldBoards.registry.registerMount(session)
        owner.updateBackend(backend(ReplacementBackend))
        runCurrent()
        assertSame(CanvasPageState.Closed, page.state.value, "backend replacement must be terminal")
        page.retry()
        page.open(canvasId, conversationId, null)
        runCurrent()
        assertSame(CanvasPageState.Closed, page.state.value)
        assertEquals(0, oldBoards.holds(session.canvasId))
        assertNull(oldBoards.registry.get(session.canvasId))
        assertEquals(0, owner.boards.value.holds(session.canvasId))
        assertNull(owner.boards.value.registry.get(session.canvasId))
        assertEquals(0, replacement.reads, "the replacement backend must never read the old identifiers")
        assertEquals(0, replacement.writes, "the replacement backend must never create or write the old identifiers")
        dispose()
        page.close()
        return page.state.value
    }

    private fun backend(key: String) = LettaConfig(key, LettaConfig.Mode.SELF_HOSTED, "https://page-hold.invalid")
}

private class TrackedStore(val backing: InMemoryCanvasDocumentStore = InMemoryCanvasDocumentStore()) : CanvasDocumentStore by backing {
    var reads = 0
    var writes = 0
    var failReads = false
    override suspend fun get(id: CanvasId): CanvasDocument? {
        reads++
        check(!failReads) { "Transient canvas storage failure" }
        return backing.get(id)
    }
    override suspend fun getForConversation(conversationId: ConversationId): CanvasDocument? {
        reads++
        return backing.getForConversation(conversationId)
    }
    override suspend fun createForConversationIfAbsent(doc: CanvasDocument): CanvasDocument {
        writes++
        return backing.createForConversationIfAbsent(doc)
    }
    override suspend fun upsert(doc: CanvasDocument) { writes++; backing.upsert(doc) }
    override suspend fun upsertIfRevision(doc: CanvasDocument, expectedRevision: Long): Boolean {
        writes++
        return backing.upsertIfRevision(doc, expectedRevision)
    }
}

private const val OriginalBackend = "page-original-backend"
private const val ReplacementBackend = "page-replacement-backend"
