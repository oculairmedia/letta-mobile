package com.letta.mobile.data.canvas.notes

import com.letta.mobile.data.canvas.NotebookDocumentId
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
