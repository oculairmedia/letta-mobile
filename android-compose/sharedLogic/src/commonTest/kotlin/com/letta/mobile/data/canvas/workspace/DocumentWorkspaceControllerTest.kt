package com.letta.mobile.data.canvas.workspace

import com.letta.mobile.data.canvas.NotebookDocument
import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.notes.NotebookNoteBackend
import com.letta.mobile.data.canvas.notes.NotebookNoteLoad
import com.letta.mobile.data.canvas.notes.NotebookNoteSave
import com.letta.mobile.data.canvas.notes.NotebookNoteSession
import com.letta.mobile.data.canvas.notes.NotebookNoteSnapshot
import com.letta.mobile.data.canvas.references.NotebookReferenceAccess
import com.letta.mobile.data.canvas.references.NotebookReferenceLoad
import com.letta.mobile.data.canvas.references.NotebookReferencePhase
import com.letta.mobile.data.canvas.references.NotebookReferenceResolver
import com.letta.mobile.data.canvas.references.NotebookReferenceSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentWorkspaceControllerTest {
    @Test
    fun supersedingNavigationAfterApplyReconcilesUndoBeforeLeaving() = runTest {
        val fixture = WorkspaceFixture()
        val controller = fixture.controller()
        controller.open(fixture.first)
        val original = controller.state.value.current!!
        original.note.edit("changed")
        val entered = CompletableDeferred<Unit>()
        fixture.afterApply = { entered.complete(Unit); awaitCancellation() }
        val obsolete = async { controller.open(fixture.second) }
        entered.await()
        original.note.edit("original")
        fixture.afterApply = {}
        assertTrue(controller.open(fixture.third))
        assertTrue(obsolete.isCancelled)
        assertEquals("original", fixture.sources.getValue(fixture.first).markdown)
        assertEquals("original", fixture.durable.getValue(fixture.first).markdown)
        assertEquals(2, fixture.saves)
        assertEquals(1, fixture.flushes)
        assertTrue(controller.back())
        assertEquals(fixture.first, controller.state.value.current?.id)
        assertFalse(controller.state.value.canGoBack)
    }

    @Test
    fun cachedMissingTargetDoesNotTouchMountedDraftHistoryOrReferenceWork() = runTest {
        assertEquals(DocumentWorkspaceProblem.UNAVAILABLE, rejectCachedFailedTarget(NotebookNoteLoad.NotFound))
    }

    @Test
    fun cachedUnavailableTargetDoesNotTouchMountedDraftHistoryOrReferenceWork() = runTest {
        assertEquals(DocumentWorkspaceProblem.UNAVAILABLE, rejectCachedFailedTarget(NotebookNoteLoad.Unavailable("read failed")))
    }

    @Test
    fun previouslyLoadedTargetMustLoadAgainBeforeItCanOpen() = runTest {
        val fixture = WorkspaceFixture()
        val cached = fixture.sessions(fixture.second)
        val controller = fixture.controller()
        assertTrue(controller.open(fixture.first))
        val mounted = controller.state.value.current
        fixture.overrideSessions = cached
        fixture.loadFailure = NotebookNoteLoad.Unavailable("later read failed")
        assertFalse(controller.open(fixture.second))
        assertSame(mounted, controller.state.value.current)
        assertEquals(DocumentWorkspaceProblem.UNAVAILABLE, controller.state.value.problem)
        assertEquals("later read failed", cached.note.state.value.error)
        assertEquals(0, fixture.saves)
        fixture.loadFailure = null
        assertTrue(controller.open(fixture.second))
        assertSame(cached, controller.state.value.current)
        assertNull(cached.note.state.value.error)
    }

    private suspend fun rejectCachedFailedTarget(failure: NotebookNoteLoad) = kotlinx.coroutines.coroutineScope {
        val fixture = WorkspaceFixture()
        val controller = fixture.controller()
        controller.open(fixture.third)
        val entered = CompletableDeferred<Unit>()
        val references = NotebookReferenceSession(fixture.first, NotebookReferenceResolver(
            authorize = { NotebookReferenceAccess.ALLOWED },
            readAuthorized = { entered.complete(Unit); awaitCancellation() },
        ))
        fixture.overrideSessions = fixture.sessions(fixture.first).copy(references = references)
        controller.open(fixture.first)
        val original = controller.state.value.current!!
        original.note.edit("keep dirty draft")
        val preview = launch { references.refresh() }
        try {
            entered.await()
            val cached = fixture.sessions(fixture.second)
            fixture.loadFailure = failure
            cached.note.refresh()
            fixture.overrideSessions = cached
            assertFalse(controller.open(fixture.second))
            assertSame(original, controller.state.value.current)
            assertEquals("keep dirty draft", original.note.state.value.draft)
            assertTrue(controller.state.value.canGoBack)
            assertTrue(preview.isActive)
            assertEquals(0, fixture.saves)
            assertEquals(0, fixture.flushes)
            val problem = controller.state.value.problem
            fixture.loadFailure = null
            fixture.overrideSessions = null
            assertTrue(controller.back())
            assertEquals(fixture.third, controller.state.value.current?.id)
            assertFalse(controller.state.value.canGoBack)
            problem
        } finally {
            preview.cancelAndJoin()
        }
    }

    @Test
    fun referencesFirstLoadsExistingNoteSessionAndUnreadableTargetDoesNotReplaceDraft() = runTest {
        val fixture = WorkspaceFixture()
        val controller = DocumentWorkspaceController { id ->
            DocumentWorkspaceOpen.Opened(DocumentWorkspaceSessions(NotebookNoteSession(id, fixture), null))
        }
        controller.present(DocumentPresentation.REFERENCES)
        assertTrue(controller.open(fixture.first))
        val original = controller.state.value.current!!
        assertEquals("original", original.note.state.value.draft)
        original.note.edit("keep draft")
        fixture.unreadable = fixture.second
        assertFalse(controller.open(fixture.second))
        assertSame(original, controller.state.value.current)
        assertEquals("keep draft", original.note.state.value.draft)
        assertEquals(DocumentWorkspaceProblem.UNAVAILABLE, controller.state.value.problem)
        assertEquals(0, fixture.saves)
        assertTrue(controller.close())
        assertNull(controller.state.value.current)
    }

    @Test
    fun presentationSwitchAndReauthorizedSelfOpenRetainTheLiveDraftWithoutSave() = runTest {
        val fixture = WorkspaceFixture()
        val controller = fixture.controller()
        assertTrue(controller.open(fixture.first))
        val original = controller.state.value.current!!
        original.note.edit("draft")
        controller.present(DocumentPresentation.REFERENCES)
        controller.present(DocumentPresentation.NOTE)
        assertTrue(controller.open(fixture.first))
        assertSame(original, controller.state.value.current)
        assertEquals("draft", original.note.state.value.draft)
        assertEquals(0, fixture.saves)
        assertEquals(listOf(fixture.first, fixture.first), fixture.requests)
        assertFalse(controller.state.value.canGoBack)
    }

    @Test
    fun deniedUnavailableAndThrowingOpenNeverSaveOrReplaceTheDraft() = runTest {
        val fixture = WorkspaceFixture()
        val controller = fixture.controller()
        controller.open(fixture.first)
        val original = controller.state.value.current!!
        original.note.edit("draft")
        for (result in listOf(DocumentWorkspaceOpen.Denied, DocumentWorkspaceOpen.Unavailable)) {
            fixture.refusal = result
            assertFalse(controller.open(fixture.second))
            assertSame(original, controller.state.value.current)
            assertEquals("draft", original.note.state.value.draft)
        }
        fixture.failure = true
        assertFalse(controller.open(fixture.second))
        assertEquals(DocumentWorkspaceProblem.UNAVAILABLE, controller.state.value.problem)
        assertEquals(0, fixture.saves)
        assertSame(original, controller.state.value.current)
    }

    @Test
    fun bothEditorAndReferenceIdentityMustExactlyMatchTheRequest() = runTest {
        val fixture = WorkspaceFixture()
        val controller = fixture.controller()
        controller.open(fixture.first)
        val original = controller.state.value.current!!
        original.note.edit("draft")
        fixture.overrideSessions = fixture.sessions(fixture.first)
        assertFalse(controller.open(fixture.second))
        assertEquals(DocumentWorkspaceProblem.IDENTITY_MISMATCH, controller.state.value.problem)
        fixture.overrideSessions = fixture.sessions(fixture.second).copy(references = fixture.sessions(fixture.first).references)
        assertFalse(controller.open(fixture.second))
        assertEquals(DocumentWorkspaceProblem.IDENTITY_MISMATCH, controller.state.value.problem)
        assertSame(original, controller.state.value.current)
        assertEquals(0, fixture.saves)
    }

    @Test
    fun failedSaveBlocksNavigationAndCloseAndRetryFlushesOnceWithoutReapplying() = runTest {
        val fixture = WorkspaceFixture()
        val controller = fixture.controller()
        controller.open(fixture.first)
        val original = controller.state.value.current!!
        original.note.edit("draft")
        fixture.persist = false
        assertFalse(controller.open(fixture.second))
        assertFalse(controller.close())
        assertSame(original, controller.state.value.current)
        assertEquals("draft", original.note.state.value.draft)
        assertEquals(DocumentWorkspaceProblem.SAVE_FAILED, controller.state.value.problem)
        fixture.persist = true
        assertTrue(controller.open(fixture.second))
        assertEquals(fixture.second, controller.state.value.current?.id)
        assertEquals(1, fixture.saves)
        assertEquals(2, fixture.flushes)
        assertEquals(NotebookReferencePhase.CANCELLED, original.references?.state?.value?.phase)
        assertTrue(controller.close())
        assertNull(controller.state.value.current)
    }

    @Test
    fun backReauthorizesExactHistoryTargetAndOnlyPopsAfterSuccess() = runTest {
        val fixture = WorkspaceFixture()
        val controller = fixture.controller()
        controller.open(fixture.first)
        controller.open(fixture.second)
        val second = controller.state.value.current!!
        second.note.edit("second draft")
        fixture.refusal = DocumentWorkspaceOpen.Denied
        assertFalse(controller.back())
        assertSame(second, controller.state.value.current)
        assertTrue(controller.state.value.canGoBack)
        assertEquals(fixture.first, fixture.requests.last())
        fixture.refusal = null
        assertTrue(controller.back())
        assertEquals(fixture.first, controller.state.value.current?.id)
        assertFalse(controller.state.value.canGoBack)
        assertEquals("second draft", fixture.sources.getValue(fixture.second).markdown)
    }

    @Test
    fun newerOpenWaitsForNonCooperativeOldOpenerAndRejectsItsLateResult() = runTest {
        val fixture = WorkspaceFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val latestEntered = CompletableDeferred<Unit>()
        var obsolete: DocumentWorkspaceSessions? = null
        val controller = DocumentWorkspaceController { id ->
            when (id) {
                fixture.second -> withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                    DocumentWorkspaceOpen.Opened(fixture.sessions(id).also { obsolete = it })
                }
                else -> {
                    if (id == fixture.third) latestEntered.complete(Unit)
                    DocumentWorkspaceOpen.Opened(fixture.sessions(id))
                }
            }
        }
        controller.open(fixture.first)
        val slow = async { controller.open(fixture.second) }
        entered.await()
        val latest = async { controller.open(fixture.third) }
        // Starting the latest request is observable via its cancellation of the older operation.
        try {
            testScheduler.runCurrent()
            assertFalse(latestEntered.isCompleted, "opener work must be serialized")
            assertEquals(fixture.first, controller.state.value.current?.id)
        } finally {
            release.complete(Unit)
        }
        assertTrue(latest.await())
        assertTrue(slow.isCancelled)
        assertEquals(fixture.third, controller.state.value.current?.id)
        assertEquals(NotebookReferencePhase.CANCELLED, obsolete?.references?.state?.value?.phase)
    }

    @Test
    fun cancellingAnOpenKeepsCurrentDraftAndCleansUpLateSessions() = runTest {
        val fixture = WorkspaceFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val target = fixture.sessions(fixture.second)
        val controller = DocumentWorkspaceController { id ->
            if (id == fixture.first) DocumentWorkspaceOpen.Opened(fixture.sessions(id)) else {
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                DocumentWorkspaceOpen.Opened(target)
            }
        }
        controller.open(fixture.first)
        val original = controller.state.value.current!!
        original.note.edit("retain")
        val pending = launch { controller.open(fixture.second) }
        entered.await()
        pending.cancel()
        release.complete(Unit)
        pending.join()
        assertSame(original, controller.state.value.current)
        assertEquals("retain", original.note.state.value.draft)
        assertFalse(controller.state.value.transitioning)
        assertEquals(NotebookReferencePhase.CANCELLED, target.references?.state?.value?.phase)
    }

    @Test
    fun editsDuringCloseSaveKeepDocumentMountedUntilAnotherCloseSucceeds() = runTest {
        val fixture = WorkspaceFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.beforeSave = { entered.complete(Unit); release.await() }
        val controller = fixture.controller()
        controller.open(fixture.first)
        val original = controller.state.value.current!!
        original.note.edit("captured")
        val closing = async { controller.close() }
        entered.await()
        original.note.edit("newer draft")
        release.complete(Unit)
        assertFalse(closing.await())
        assertSame(original, controller.state.value.current)
        assertEquals("newer draft", original.note.state.value.draft)
        assertTrue(controller.close())
        assertEquals("newer draft", fixture.sources.getValue(fixture.first).markdown)
        assertNull(controller.state.value.current)
    }

    @Test
    fun successfulNavigationCancelsSuspendedReferenceWorkButDenialDoesNot() = runTest {
        val fixture = WorkspaceFixture()
        val entered = CompletableDeferred<Unit>()
        val references = NotebookReferenceSession(fixture.first, NotebookReferenceResolver(
            authorize = { NotebookReferenceAccess.ALLOWED },
            readAuthorized = { entered.complete(Unit); awaitCancellation() },
        ))
        fixture.overrideSessions = fixture.sessions(fixture.first).copy(references = references)
        val controller = fixture.controller()
        controller.open(fixture.first)
        val preview = launch { references.refresh() }
        entered.await()
        fixture.overrideSessions = null
        fixture.refusal = DocumentWorkspaceOpen.Denied
        assertFalse(controller.open(fixture.second))
        assertTrue(preview.isActive)
        fixture.refusal = null
        assertTrue(controller.open(fixture.second))
        preview.join()
        assertTrue(preview.isCancelled)
        assertEquals(NotebookReferencePhase.CANCELLED, references.state.value.phase)
    }

    @Test
    fun closeSupersedesPendingOpenAndCannotBeUndoneByLateTarget() = runTest {
        val fixture = WorkspaceFixture()
        val entered = CompletableDeferred<Unit>()
        val controller = DocumentWorkspaceController { id ->
            if (id == fixture.second) { entered.complete(Unit); awaitCancellation() }
            DocumentWorkspaceOpen.Opened(fixture.sessions(id))
        }
        controller.open(fixture.first)
        val pending = launch { controller.open(fixture.second) }
        entered.await()
        assertTrue(controller.close())
        pending.cancelAndJoin()
        assertNull(controller.state.value.current)
        assertFalse(controller.state.value.canGoBack)
    }
}

private class WorkspaceFixture : NotebookNoteBackend {
    val first = NotebookDocumentId("00112233445566778899aabbccddeeff")
    val second = NotebookDocumentId("ffeeddccbbaa99887766554433221100")
    val third = NotebookDocumentId("11112222333344445555666677778888")
    val sources = listOf(first, second, third).associateWith {
        NotebookNoteSnapshot(it, "Document ${it.value}", "original")
    }.toMutableMap()
    val requests = mutableListOf<NotebookDocumentId>()
    val durable = sources.toMutableMap()
    var refusal: DocumentWorkspaceOpen? = null
    var overrideSessions: DocumentWorkspaceSessions? = null
    var failure = false
    var persist = true
    var unreadable: NotebookDocumentId? = null
    var saves = 0
    var flushes = 0
    var beforeSave: suspend () -> Unit = {}
    var afterApply: suspend () -> Unit = {}
    var loadFailure: NotebookNoteLoad? = null

    fun controller() = DocumentWorkspaceController { id ->
        requests.add(id)
        if (failure) error("source unavailable")
        refusal ?: DocumentWorkspaceOpen.Opened(overrideSessions ?: sessions(id))
    }

    suspend fun sessions(id: NotebookDocumentId): DocumentWorkspaceSessions {
        val note = NotebookNoteSession(id, this)
        note.refresh()
        val references = NotebookReferenceSession(id, NotebookReferenceResolver(
            authorize = { NotebookReferenceAccess.ALLOWED },
            readAuthorized = { NotebookReferenceLoad.Found(NotebookDocument(it, "Document")) },
        ))
        return DocumentWorkspaceSessions(note, references)
    }

    override suspend fun load(id: NotebookDocumentId): NotebookNoteLoad =
        loadFailure ?: if (id == unreadable) NotebookNoteLoad.Unavailable("read failed") else NotebookNoteLoad.Found(sources.getValue(id))
    override suspend fun save(id: NotebookDocumentId, expected: String, replacement: String): NotebookNoteSave {
        saves++
        beforeSave()
        val source = sources.getValue(id)
        if (source.markdown != expected) return NotebookNoteSave.Conflict(source)
        val applied = source.copy(markdown = replacement)
        sources[id] = applied
        afterApply()
        if (persist) durable[id] = applied
        return if (persist) NotebookNoteSave.Saved(applied) else NotebookNoteSave.PendingDurability(applied, "disk failed")
    }
    override suspend fun flush(applied: NotebookNoteSnapshot): NotebookNoteSave {
        flushes++
        if (persist) durable[applied.id] = sources.getValue(applied.id)
        return if (persist) NotebookNoteSave.Saved(applied) else NotebookNoteSave.PendingDurability(applied, "disk failed")
    }
}
