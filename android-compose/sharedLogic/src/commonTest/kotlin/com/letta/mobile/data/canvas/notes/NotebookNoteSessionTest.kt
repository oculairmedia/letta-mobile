package com.letta.mobile.data.canvas.notes

import com.letta.mobile.data.canvas.NotebookDocumentId
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotebookNoteSessionTest {
    private val id = NotebookDocumentId("00112233445566778899aabbccddeeff")

    @Test
    fun postBarrierPeerUndoCannotCloseUntilCurrentTextIsDurable() = runTest {
        val backend = NoteTestBackend(id)
        val session = cancelledUndoSession(backend)
        backend.source = backend.source.copy(markdown = "persisted peer")
        val barrier = NoteOperationGate()
        backend.afterPersistGate = barrier
        val closing = async { session.prepareClose() }
        barrier.entered.await()
        assertEquals("persisted peer", backend.durable.markdown)
        // This update is newer than the barrier snapshot, and happens to equal our old baseline.
        backend.source = backend.source.copy(markdown = "original")
        barrier.release.complete(Unit)
        assertFalse(closing.await(), "a durable peer snapshot does not acknowledge the newer current text")
        assertEquals("original", session.state.value.baseline?.markdown)
        assertEquals("persisted peer", session.state.value.durable?.markdown)
        assertEquals("original", session.state.value.observed?.markdown)
        assertEquals("changed", session.state.value.indeterminateWrite?.markdown)
        assertTrue(session.state.value.dirty)
        backend.afterPersistGate = null
        backend.persist = false
        assertFalse(session.retryPersistence())
        assertFalse(session.prepareClose())
        assertFalse(session.save())
        assertFalse(session.discardAndReload())
        session.refresh()
        assertTrue(session.state.value.dirty)
        assertEquals("persisted peer", backend.durable.markdown)
        assertEquals("original", session.state.value.draft)
        backend.persist = true
        assertTrue(session.retryPersistence())
        assertNull(session.state.value.indeterminateWrite)
        assertTrue(session.prepareClose())
        assertEquals(session.state.value.draft, backend.durable.markdown)
        assertEquals("original", backend.source.markdown)
        assertEquals(1, backend.saves, "reconciliation must flush without applying another replacement")
    }

    @Test
    fun postBarrierPeerConflictRetryPersistsCurrentPeerWithoutOverwritingIt() = runTest {
        val backend = NoteTestBackend(id)
        val session = cancelledUndoSession(backend)
        backend.source = backend.source.copy(markdown = "persisted peer")
        val barrier = NoteOperationGate()
        backend.afterPersistGate = barrier
        val closing = async { session.prepareClose() }
        barrier.entered.await()
        backend.source = backend.source.copy(markdown = "newer peer")
        barrier.release.complete(Unit)
        assertFalse(closing.await())
        assertEquals("persisted peer", backend.durable.markdown)
        assertEquals("original", session.state.value.baseline?.markdown)
        assertEquals("persisted peer", session.state.value.applied?.markdown)
        assertEquals("persisted peer", session.state.value.durable?.markdown)
        assertEquals("newer peer", session.state.value.observed?.markdown)
        assertEquals("changed", session.state.value.indeterminateWrite?.markdown)
        assertEquals("original", session.state.value.draft)
        assertFalse(session.state.value.saving)
        backend.afterPersistGate = null
        assertTrue(session.retryPersistence())
        assertNull(session.state.value.indeterminateWrite)
        assertEquals("original", session.state.value.baseline?.markdown)
        assertEquals("newer peer", session.state.value.applied?.markdown)
        assertEquals("newer peer", session.state.value.durable?.markdown)
        assertEquals("newer peer", backend.durable.markdown)
        assertEquals("newer peer", backend.source.markdown)
        assertTrue(session.state.value.conflict)
        assertFalse(session.save())
        assertFalse(session.prepareClose())
        assertEquals("original", session.state.value.draft)
        assertEquals(1, backend.saves)
        assertTrue(session.discardAndReload())
        assertTrue(session.prepareClose())
        assertEquals(session.state.value.draft, backend.durable.markdown)
    }

    private suspend fun cancelledUndoSession(backend: NoteTestBackend): NotebookNoteSession = coroutineScope {
        val session = NotebookNoteSession(id, backend)
        session.refresh()
        session.edit("changed")
        val applied = NoteOperationGate()
        backend.afterApplyGate = applied
        val saving = async { session.save() }
        applied.entered.await()
        saving.cancelAndJoin()
        session.edit("original")
        backend.afterApplyGate = null
        session
    }

    @Test
    fun cancelledAppliedWriteRemainsDirtyAfterUndoAndRefreshUntilDurableClose() = runTest {
        val backend = NoteTestBackend(id)
        val session = NotebookNoteSession(id, backend)
        session.refresh()
        session.edit("changed")
        val gate = NoteOperationGate()
        backend.afterApplyGate = gate
        val saving = async { session.save() }
        gate.entered.await()
        saving.cancelAndJoin()
        session.edit("original")
        session.refresh()
        assertTrue(session.state.value.dirty)
        assertEquals("changed", session.state.value.indeterminateWrite?.markdown)
        assertEquals("original", session.state.value.draft)
        backend.afterApplyGate = null
        assertTrue(session.prepareClose())
        assertNull(session.state.value.indeterminateWrite)
        assertEquals("original", backend.source.markdown)
        assertEquals("original", backend.durable.markdown)
        assertEquals(2, backend.saves)
        assertEquals(1, backend.flushes)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun indeterminateReadAndFlushEachRespectOneBoundedAttemptAndRetainDraftOnTimeout() = runTest {
        for (stallRead in listOf(true, false)) {
            val backend = NoteTestBackend(id)
            val session = NotebookNoteSession(id, backend)
            session.refresh()
            session.edit("changed")
            val applied = NoteOperationGate()
            backend.afterApplyGate = applied
            val saving = async { session.save() }
            applied.entered.await()
            saving.cancelAndJoin()
            session.edit("original")
            val gate = NoteOperationGate()
            if (stallRead) backend.loadGate = gate else backend.flushGate = gate
            val closing = async { session.prepareClose() }
            try {
                testScheduler.advanceUntilIdle()
                assertTrue(closing.isCompleted, "the reconciliation deadline must release the operation")
                assertFalse(closing.await())
                assertTrue(session.state.value.dirty)
                assertTrue(session.state.value.error!!.contains("timed out"))
                assertEquals(2, backend.loads, "one reconciliation read, with no retry loop")
                assertEquals(if (stallRead) 0 else 1, backend.flushes)
            } finally {
                closing.cancelAndJoin()
                gate.release.complete(Unit)
            }
            backend.afterApplyGate = null
            assertTrue(session.prepareClose())
            assertEquals("original", backend.durable.markdown)
        }
    }

    @Test
    fun cancellingReconciliationFlushPreservesIndeterminateWriteForNextClose() = runTest {
        val backend = NoteTestBackend(id)
        val session = NotebookNoteSession(id, backend)
        session.refresh()
        session.edit("changed")
        val applied = NoteOperationGate()
        backend.afterApplyGate = applied
        val saving = async { session.save() }
        applied.entered.await()
        saving.cancelAndJoin()
        session.edit("original")
        val flushing = NoteOperationGate()
        backend.flushGate = flushing
        val closing = async { session.prepareClose() }
        flushing.entered.await()
        closing.cancelAndJoin()
        assertTrue(closing.isCancelled)
        assertFalse(session.state.value.saving)
        assertTrue(session.state.value.dirty)
        assertEquals("changed", session.state.value.indeterminateWrite?.markdown)
        backend.flushGate = null
        backend.afterApplyGate = null
        assertTrue(session.prepareClose())
        assertEquals("original", backend.durable.markdown)
    }

    @Test
    fun indeterminatePersistenceFailureRefusesCloseAndRetryPersistsUndo() = runTest {
        val backend = NoteTestBackend(id)
        val session = NotebookNoteSession(id, backend)
        session.refresh()
        session.edit("changed")
        val applied = NoteOperationGate()
        backend.afterApplyGate = applied
        val saving = async { session.save() }
        applied.entered.await()
        saving.cancelAndJoin()
        session.edit("original")
        backend.persist = false
        assertFalse(session.prepareClose())
        assertTrue(session.state.value.dirty)
        assertEquals("disk failed", session.state.value.error)
        assertEquals("original", session.state.value.draft)
        backend.persist = true
        backend.afterApplyGate = null
        assertTrue(session.prepareClose())
        assertEquals("original", backend.durable.markdown)
        assertEquals(2, backend.saves)
        assertEquals(2, backend.flushes)
    }

    @Test
    fun reconciliationPersistsHistoryButNeverOverwritesUnrelatedPeerText() = runTest {
        val backend = NoteTestBackend(id)
        val session = NotebookNoteSession(id, backend)
        session.refresh()
        session.edit("changed")
        val applied = NoteOperationGate()
        backend.afterApplyGate = applied
        val saving = async { session.save() }
        applied.entered.await()
        saving.cancelAndJoin()
        session.edit("original")
        backend.source = backend.source.copy(markdown = "peer")
        assertFalse(session.prepareClose())
        assertTrue(session.state.value.conflict)
        assertNull(session.state.value.indeterminateWrite)
        assertEquals("original", session.state.value.draft)
        assertEquals("peer", session.state.value.observed?.markdown)
        assertEquals("peer", backend.durable.markdown)
        assertEquals(1, backend.saves)
    }

    @Test
    fun staleSaveAndRefreshRetainBothDraftAndSource() = runTest {
        val backend = NoteTestBackend(id)
        val session = NotebookNoteSession(id, backend)
        session.refresh()
        session.edit("my draft")
        backend.source = backend.source.copy(markdown = "peer changed")
        assertFalse(session.save())
        assertTrue(session.state.value.conflict)
        assertEquals("my draft", session.state.value.draft)
        assertEquals("peer changed", session.state.value.observed?.markdown)
        session.refresh()
        assertEquals("original", session.state.value.baseline?.markdown)
        assertEquals("my draft", session.state.value.draft)
        assertFalse(session.prepareClose())
        assertTrue(session.discardAndReload())
        assertEquals("peer changed", session.state.value.draft)
        assertFalse(session.state.value.dirty)
    }

    @Test
    fun pendingDurabilityRetriesFlushWithoutRepeatingMutation() = runTest {
        val backend = NoteTestBackend(id).apply { persist = false }
        val session = NotebookNoteSession(id, backend)
        session.refresh()
        session.edit("applied")
        assertFalse(session.save())
        assertEquals("applied", session.state.value.applied?.markdown)
        assertNull(session.state.value.durable)
        assertTrue(session.state.value.pendingDurability)
        assertTrue(session.state.value.dirty)
        assertFalse(session.prepareClose())
        assertFalse(session.discardAndReload())
        session.refresh()
        assertTrue(session.state.value.pendingDurability, "reading the applied text cannot acknowledge durability")
        backend.persist = true
        assertTrue(session.retryPersistence())
        assertEquals(1, backend.saves)
        assertEquals(3, backend.flushes)
        assertFalse(session.state.value.dirty)
        assertEquals(session.state.value.applied, session.state.value.durable)
        assertTrue(session.prepareClose())
    }

    @Test
    fun retryAfterPeerChangePersistsHistoryAndKeepsConflictAndDraft() = runTest {
        val backend = NoteTestBackend(id).apply { persist = false }
        val session = NotebookNoteSession(id, backend)
        session.refresh()
        session.edit("mine")
        session.save()
        backend.source = backend.source.copy(markdown = "peer")
        backend.persist = true
        assertTrue(session.retryPersistence())
        assertEquals(1, backend.saves)
        assertFalse(session.state.value.pendingDurability)
        assertEquals("mine", session.state.value.durable?.markdown)
        assertEquals("mine", session.state.value.draft)
        assertEquals("peer", session.state.value.observed?.markdown)
        assertTrue(session.state.value.conflict)
        assertFalse(session.prepareClose())
    }

    @Test
    fun editsWhileSaveSuspendsStayDirtyUntilNextExplicitSave() = runTest {
        val backend = NoteTestBackend(id)
        val session = NotebookNoteSession(id, backend)
        session.refresh()
        session.edit("first")
        val gate = NoteOperationGate()
        backend.saveGate = gate
        val save = async { session.save() }
        gate.entered.await()
        assertTrue(session.state.value.saving)
        session.edit("second")
        gate.release.complete(Unit)
        assertFalse(save.await())
        assertEquals("first", session.state.value.durable?.markdown)
        assertEquals("second", session.state.value.draft)
        assertTrue(session.state.value.dirty)
        assertTrue(session.save())
        assertEquals("second", backend.source.markdown)
    }

    @Test
    fun separateDocumentSessionsCannotReceiveEachOthersAsyncResults() = runTest {
        val backendA = NoteTestBackend(id)
        val idB = NotebookDocumentId("ffeeddccbbaa99887766554433221100")
        val backendB = NoteTestBackend(idB)
        val a = NotebookNoteSession(id, backendA)
        val b = NotebookNoteSession(idB, backendB)
        a.refresh()
        b.refresh()
        a.edit("A save")
        val gate = NoteOperationGate()
        backendA.saveGate = gate
        val savingA = async { a.save() }
        gate.entered.await()
        b.edit("B draft")
        val before = b.state.value
        gate.release.complete(Unit)
        assertTrue(savingA.await())
        assertEquals(before, b.state.value)
        assertEquals(idB, b.state.value.id)
    }

    @Test
    fun cancelledSavePropagatesAndReleasesOperationWithoutLosingDraft() = runTest {
        val backend = NoteTestBackend(id)
        val session = NotebookNoteSession(id, backend)
        session.refresh()
        session.edit("retained")
        val gate = NoteOperationGate()
        backend.saveGate = gate
        val saving = async { session.save() }
        gate.entered.await()
        saving.cancelAndJoin()
        assertTrue(saving.isCancelled)
        assertFalse(session.state.value.saving)
        assertTrue(session.state.value.dirty)
        assertEquals("retained", session.state.value.draft)
        backend.saveGate = null
        assertTrue(session.save())
    }

    @Test
    fun failedAndCancelledRefreshNeverReplaceDraftWithEmptyText() = runTest {
        val backend = NoteTestBackend(id)
        val session = NotebookNoteSession(id, backend)
        session.refresh()
        session.edit("retained")
        backend.loadResult = NotebookNoteLoad.Unavailable("read failed")
        session.refresh()
        assertEquals("read failed", session.state.value.error)
        assertEquals("retained", session.state.value.draft)
        val gate = NoteOperationGate()
        backend.loadGate = gate
        val loading = async { session.refresh() }
        gate.entered.await()
        loading.cancelAndJoin()
        assertTrue(loading.isCancelled)
        assertFalse(session.state.value.loading)
        assertEquals("retained", session.state.value.draft)
    }

    @Test
    fun notFoundAndEmptyAreDistinctAndCannotCloseUnloadedNotes() = runTest {
        val backend = NoteTestBackend(id)
        val session = NotebookNoteSession(id, backend)
        backend.loadResult = NotebookNoteLoad.NotFound
        session.refresh()
        assertTrue(session.state.value.notFound)
        assertNull(session.state.value.baseline)
        assertFalse(session.prepareClose())
        backend.loadResult = null
        backend.source = backend.source.copy(markdown = "")
        session.refresh()
        assertFalse(session.state.value.notFound)
        assertEquals("", session.state.value.baseline?.markdown)
        assertTrue(session.prepareClose())
    }

    @Test
    fun editsDuringReloadAndCloseAreNotDiscardedOrAcknowledged() = runTest {
        val backend = NoteTestBackend(id)
        val session = NotebookNoteSession(id, backend)
        session.refresh()
        session.edit("discard this")
        val loadGate = NoteOperationGate()
        backend.loadGate = loadGate
        val reload = async { session.discardAndReload() }
        loadGate.entered.await()
        session.edit("new edit")
        loadGate.release.complete(Unit)
        reload.await()
        assertEquals("new edit", session.state.value.draft)
        val saveGate = NoteOperationGate()
        backend.saveGate = saveGate
        val closing = async { session.prepareClose() }
        saveGate.entered.await()
        session.edit("stay open")
        saveGate.release.complete(Unit)
        assertFalse(closing.await())
        assertTrue(session.state.value.dirty)
    }

    @Test
    fun unavailableSaveRetainsDraftAndBaseline() = runTest {
        val backend = NoteTestBackend(id)
        val session = NotebookNoteSession(id, backend)
        session.refresh()
        session.edit("draft")
        backend.saveResult = NotebookNoteSave.Unavailable("read only")
        assertFalse(session.save())
        assertEquals("draft", session.state.value.draft)
        assertEquals("original", session.state.value.baseline?.markdown)
        assertEquals("read only", session.state.value.error)
        assertFalse(session.prepareClose())
        assertIs<NotebookNoteLoad.Found>(backend.load(id))
    }
}
