package com.letta.mobile.ui.document.workspace

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.NotebookDocument
import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.NotebookItem
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
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceController
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceOpen
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceSessions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DocumentWorkspaceTest {
    @Test
    fun closeWhileUiSaveIsSuspendedWaitsForSaveAndDurableUndoAfterLensSwitch() = runComposeUiTest {
        val fixture = WorkspaceUiFixture()
        runBlocking { fixture.controller.open(fixture.root) }
        val original = fixture.controller.state.value.current!!
        setContent { MaterialTheme { DocumentWorkspace(fixture.controller, Modifier.size(360.dp, 700.dp)) } }
        onNodeWithTag("notebook-note-markdown").performTextReplacement("changed")
        runOnIdle { fixture.afterApplyRelease = CompletableDeferred() }
        onNodeWithText("Save", substring = false).performClick()
        runOnIdle { assertTrue(fixture.afterApplyEntered.isCompleted) }
        onNodeWithTag("document-presentation-references").performClick()
        onNodeWithTag("document-presentation-note").performClick()
        onNodeWithTag("notebook-note-markdown").performTextReplacement("root body")
        onNodeWithText("Close document").performClick()
        try {
            runOnIdle {
                assertSame(original, fixture.controller.state.value.current)
                assertTrue(fixture.controller.state.value.transitioning, "close must wait for the suspended UI save")
                assertTrue(original.note.state.value.saving, "lens disposal must not cancel the save")
                assertEquals("root body", fixture.durable.getValue(fixture.root).markdown)
                assertEquals(1, fixture.saves)
            }
        } finally {
            runOnIdle { fixture.afterApplyRelease?.complete(Unit) }
        }
        waitUntil { fixture.controller.state.value.current == null }
        onNodeWithText("Open a document to begin.").assertIsDisplayed()
        runOnIdle {
            assertEquals("root body", fixture.durable.getValue(fixture.root).markdown)
            assertEquals(2, fixture.saves)
        }
    }

    @Test
    fun targetNavigationDuringSuspendedUiSaveRetainsEditorOnFailureAndRetriesDurableUndo() = runComposeUiTest {
        val fixture = WorkspaceUiFixture()
        runBlocking { fixture.controller.open(fixture.root) }
        val original = fixture.controller.state.value.current!!
        setContent { MaterialTheme { DocumentWorkspace(fixture.controller, Modifier.size(360.dp, 700.dp)) } }
        onNodeWithTag("notebook-note-markdown").performTextReplacement("changed")
        runOnIdle { fixture.afterApplyRelease = CompletableDeferred() }
        onNodeWithText("Save", substring = false).performClick()
        runOnIdle { assertTrue(fixture.afterApplyEntered.isCompleted) }
        onNodeWithTag("notebook-note-markdown").performTextReplacement("root body")
        onNodeWithTag("document-presentation-references").performClick()
        onAllNodesWithText("Open document")[1].performScrollTo().performClick()
        try {
            runOnIdle {
                assertSame(original, fixture.controller.state.value.current)
                assertTrue(original.note.state.value.saving)
                assertTrue(fixture.controller.state.value.transitioning)
                assertEquals(1, fixture.saves)
                fixture.persist = false
            }
        } finally {
            runOnIdle { fixture.afterApplyRelease?.complete(Unit) }
        }
        waitUntil { !fixture.controller.state.value.transitioning }
        onNodeWithText("Save did not complete.", substring = true).assertIsDisplayed()
        runOnIdle {
            assertSame(original, fixture.controller.state.value.current)
            assertEquals("root body", original.note.state.value.draft)
            assertTrue(original.note.state.value.pendingDurability)
            assertFalse(fixture.controller.state.value.canGoBack)
            fixture.persist = true
        }
        onAllNodesWithText("Open document")[1].performScrollTo().performClick()
        waitUntil { fixture.controller.state.value.current?.id == fixture.target }
        runOnIdle {
            assertEquals("root body", fixture.durable.getValue(fixture.root).markdown)
            assertEquals(2, fixture.saves)
            assertTrue(fixture.controller.state.value.canGoBack)
        }
    }

    @Test
    fun editNoteReferencesNoteRetainsDraftAcrossCompactAndWideLayouts() = runComposeUiTest {
        val fixture = WorkspaceUiFixture()
        runBlocking { fixture.controller.open(fixture.root) }
        val original = fixture.controller.state.value.current!!
        val width = mutableStateOf(360.dp)
        setContent { MaterialTheme { DocumentWorkspace(fixture.controller, Modifier.size(width.value, 700.dp)) } }
        onNodeWithTag("document-workspace-compact").assertIsDisplayed()
        onNodeWithTag("notebook-note-markdown").performTextReplacement("# unsaved draft")
        onNodeWithTag("document-presentation-references").performClick()
        onNodeWithText("Embedded target").assertIsDisplayed()
        onNodeWithTag("document-presentation-note").performClick()
        onNodeWithTag("notebook-note-markdown").assertTextContains("# unsaved draft")
        runOnIdle { width.value = 1000.dp }
        onNodeWithTag("document-workspace-wide").assertIsDisplayed()
        onNodeWithTag("document-presentation-references").performClick()
        onNodeWithText("Embedded target").assertIsDisplayed()
        onNodeWithTag("document-presentation-note").performClick()
        onNodeWithTag("notebook-note-markdown").assertTextContains("# unsaved draft")
        runOnIdle {
            assertSame(original, fixture.controller.state.value.current)
            assertEquals(0, fixture.saves)
            assertEquals(listOf(fixture.root), fixture.requests)
        }
    }

    @Test
    fun embeddedTargetClickOpensExactIdThroughFreshAuthorizationAndBackReauthorizes() = runComposeUiTest {
        val fixture = WorkspaceUiFixture()
        runBlocking { fixture.controller.open(fixture.root) }
        setContent { MaterialTheme { DocumentWorkspace(fixture.controller, Modifier.size(1000.dp, 700.dp)) } }
        onNodeWithTag("document-presentation-references").performClick()
        onAllNodesWithText("Open document")[1].performScrollTo().performClick()
        runOnIdle {
            assertSame(fixture.target, fixture.requests.last())
            assertEquals(fixture.target, fixture.controller.state.value.current?.id)
        }
        onNodeWithTag("document-presentation-note").performClick()
        onNodeWithTag("notebook-note-markdown").assertTextContains("target body")
        onNodeWithText("Back", substring = false).performClick()
        onNodeWithTag("notebook-note-markdown").assertTextContains("root body")
        runOnIdle { assertEquals(listOf(fixture.root, fixture.target, fixture.root), fixture.requests) }
    }

    @Test
    fun failedSaveBlocksTargetNavigationAndCloseAndGestureRetriesSucceed() = runComposeUiTest {
        val fixture = WorkspaceUiFixture()
        runBlocking { fixture.controller.open(fixture.root) }
        val original = fixture.controller.state.value.current!!
        setContent { MaterialTheme { DocumentWorkspace(fixture.controller, Modifier.size(360.dp, 700.dp)) } }
        onNodeWithTag("notebook-note-markdown").performTextReplacement("draft to retain")
        runOnIdle { fixture.persist = false }
        onNodeWithTag("document-presentation-references").performClick()
        onAllNodesWithText("Open document")[1].performScrollTo().performClick()
        onNodeWithText("Save did not complete.", substring = true).assertIsDisplayed()
        onNodeWithText("Close document").performClick()
        onNodeWithTag("document-presentation-note").performClick()
        onNodeWithTag("notebook-note-markdown").assertTextContains("draft to retain")
        runOnIdle {
            assertSame(original, fixture.controller.state.value.current)
            fixture.persist = true
        }
        onNodeWithTag("document-presentation-references").performClick()
        onAllNodesWithText("Open document")[1].performScrollTo().performClick()
        onNodeWithTag("document-presentation-note").performClick()
        onNodeWithTag("notebook-note-markdown").assertTextContains("target body")
        runOnIdle { assertEquals(1, fixture.saves) }
        onNodeWithTag("notebook-note-markdown").performTextReplacement("close draft")
        runOnIdle { fixture.persist = false }
        onNodeWithText("Close document").performClick()
        onNodeWithTag("notebook-note-markdown").assertTextContains("close draft")
        runOnIdle { fixture.persist = true }
        onNodeWithText("Close document").performClick()
        onNodeWithText("Open a document to begin.").assertIsDisplayed()
        onNodeWithTag("notebook-note-editor").assertDoesNotExist()
    }

    @Test
    fun deniedEmbeddedOpenDoesNotInferAccessFromReadablePreviewOrLoseDraft() = runComposeUiTest {
        val fixture = WorkspaceUiFixture()
        runBlocking { fixture.controller.open(fixture.root) }
        val original = fixture.controller.state.value.current!!
        setContent { MaterialTheme { DocumentWorkspace(fixture.controller, Modifier.size(360.dp, 700.dp)) } }
        onNodeWithTag("notebook-note-markdown").performTextReplacement("private draft")
        onNodeWithTag("document-presentation-references").performClick()
        onNodeWithText("Embedded target").assertIsDisplayed()
        runOnIdle { fixture.deny = fixture.target }
        onAllNodesWithText("Open document")[1].performScrollTo().performClick()
        onNodeWithText("Access to the requested document was denied.", substring = true).assertIsDisplayed()
        onNodeWithTag("document-presentation-note").performClick()
        onNodeWithTag("notebook-note-markdown").assertTextContains("private draft")
        runOnIdle {
            assertSame(original, fixture.controller.state.value.current)
            assertEquals(fixture.target, fixture.requests.last())
            assertEquals(0, fixture.saves)
        }
    }

    @Test
    fun cancelledStaleTargetCannotMountAfterNewerEmbeddedTargetGesture() = runComposeUiTest {
        val fixture = WorkspaceUiFixture()
        runBlocking { fixture.controller.open(fixture.root) }
        fixture.slow = fixture.target
        setContent { MaterialTheme { DocumentWorkspace(fixture.controller, Modifier.size(1000.dp, 700.dp)) } }
        onNodeWithTag("document-presentation-references").performClick()
        onAllNodesWithText("Open document")[1].performScrollTo().performClick()
        try {
            runOnIdle { assertTrue(fixture.openEntered.isCompleted) }
            onAllNodesWithText("Open document")[2].performScrollTo().performClick()
            runOnIdle { assertEquals(fixture.root, fixture.controller.state.value.current?.id) }
        } finally {
            runOnIdle { fixture.openRelease.complete(Unit) }
        }
        waitUntil { fixture.controller.state.value.current?.id == fixture.latest }
        onNodeWithTag("document-presentation-note").performClick()
        onNodeWithTag("notebook-note-markdown").assertTextContains("latest body")
        runOnIdle {
            assertEquals(NotebookReferencePhase.CANCELLED, fixture.obsolete?.references?.state?.value?.phase)
            assertEquals(listOf(fixture.root, fixture.target, fixture.latest), fixture.requests)
            assertFalse(fixture.controller.state.value.transitioning)
        }
    }

    @Test
    fun missingReferenceBackendHasExplicitCapabilityStateAndNoteRemainsEditable() = runComposeUiTest {
        val fixture = WorkspaceUiFixture().apply { supportsReferences = false }
        runBlocking { fixture.controller.open(fixture.root) }
        setContent { MaterialTheme { DocumentWorkspace(fixture.controller, Modifier.size(360.dp, 700.dp)) } }
        onNodeWithTag("document-presentation-references").performClick()
        onNodeWithText("References are not supported by this document source.").assertIsDisplayed()
        onNodeWithTag("document-presentation-note").performClick()
        onNodeWithTag("notebook-note-markdown").performTextReplacement("still editable")
        onNodeWithTag("notebook-note-markdown").assertTextContains("still editable")
    }
}

private class WorkspaceUiFixture : NotebookNoteBackend {
    val root = NotebookDocumentId("00112233445566778899aabbccddeeff")
    val target = NotebookDocumentId("ffeeddccbbaa99887766554433221100")
    val latest = NotebookDocumentId("11112222333344445555666677778888")
    val requests = mutableListOf<NotebookDocumentId>()
    private val sources = mutableMapOf(
        root to NotebookNoteSnapshot(root, "Root document", "root body"),
        target to NotebookNoteSnapshot(target, "Embedded target", "target body"),
        latest to NotebookNoteSnapshot(latest, "Latest target", "latest body"),
    )
    var persist = true
    val durable = sources.toMutableMap()
    val afterApplyEntered = CompletableDeferred<Unit>()
    var afterApplyRelease: CompletableDeferred<Unit>? = null
    var saves = 0
    var supportsReferences = true
    var deny: NotebookDocumentId? = null
    var slow: NotebookDocumentId? = null
    var obsolete: DocumentWorkspaceSessions? = null
    val openEntered = CompletableDeferred<Unit>()
    val openRelease = CompletableDeferred<Unit>()
    val controller = DocumentWorkspaceController { id ->
        requests.add(id)
        if (id == deny) DocumentWorkspaceOpen.Denied else {
            val sessions = if (id == slow) withContext(NonCancellable) {
                openEntered.complete(Unit)
                openRelease.await()
                sessions(id).also { obsolete = it }
            } else sessions(id)
            DocumentWorkspaceOpen.Opened(sessions)
        }
    }

    private suspend fun sessions(id: NotebookDocumentId): DocumentWorkspaceSessions {
        val note = NotebookNoteSession(id, this)
        note.refresh()
        val references = if (!supportsReferences) null else NotebookReferenceSession(id, NotebookReferenceResolver(
            authorize = { NotebookReferenceAccess.ALLOWED },
            readAuthorized = {
                val source = sources.getValue(it)
                val items = if (it == root) listOf(
                    NotebookItem("embed", "reference", targetDocumentId = target),
                    NotebookItem("latest", "reference", targetDocumentId = latest),
                ) else emptyList()
                NotebookReferenceLoad.Found(NotebookDocument(it, source.title, source.markdown, items = items))
            },
        ))
        return DocumentWorkspaceSessions(note, references)
    }

    override suspend fun load(id: NotebookDocumentId) = NotebookNoteLoad.Found(sources.getValue(id))
    override suspend fun save(id: NotebookDocumentId, expected: String, replacement: String): NotebookNoteSave {
        saves++
        val source = sources.getValue(id)
        if (source.markdown != expected) return NotebookNoteSave.Conflict(source)
        val applied = source.copy(markdown = replacement)
        sources[id] = applied
        afterApplyEntered.complete(Unit)
        afterApplyRelease?.await()
        return persisted(applied)
    }
    override suspend fun flush(applied: NotebookNoteSnapshot): NotebookNoteSave = persisted(applied)
    private fun persisted(applied: NotebookNoteSnapshot): NotebookNoteSave {
        if (persist) durable[applied.id] = sources.getValue(applied.id)
        return if (persist) NotebookNoteSave.Saved(applied) else NotebookNoteSave.PendingDurability(applied, "disk failed")
    }
}
