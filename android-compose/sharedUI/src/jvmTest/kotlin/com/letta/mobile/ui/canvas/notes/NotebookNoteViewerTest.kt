@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas.notes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasNotebookIdentity
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.notes.NotebookNoteBackend
import com.letta.mobile.data.canvas.notes.NotebookNoteLoad
import com.letta.mobile.data.canvas.notes.NotebookNoteSave
import com.letta.mobile.data.canvas.notes.NotebookNoteSnapshot
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.ui.canvas.CanvasDocumentViewerHost
import com.letta.mobile.ui.canvas.canvasViewerOptionTag
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerId
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerRegistry
import com.letta.mobile.ui.canvas.plugin.ProvideCanvasDocumentViewerRegistry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The note view of a canvas (letta-mobile-n2stx.2.1, B3): it edits the canvas's own notebook, and
 * leaving it saves the draft first and stays put while the save cannot land.
 */
class NotebookNoteViewerTest {
    @Test
    fun theNoteViewEditsTheCanvasNotebook() = runComposeUiTest {
        val backend = ViewerTestBackend()
        mount(backend, identity = FixedIdentity(NOTEBOOK))
        onNodeWithTag(canvasViewerOptionTag(NOTEBOOK_NOTE_VIEWER_ID)).performClick()

        onNodeWithTag(MARKDOWN).assertTextContains("original")
        runOnIdle { assertEquals(listOf(NOTEBOOK), backend.loaded.distinct()) }
    }

    @Test
    fun aCanvasWithoutANotebookSaysSo() = runComposeUiTest {
        mount(ViewerTestBackend(), identity = FixedIdentity(null))
        onNodeWithTag(canvasViewerOptionTag(NOTEBOOK_NOTE_VIEWER_ID)).performClick()

        onNodeWithTag(NOTEBOOK_NOTE_MISSING_TAG).assertExists()
    }

    @Test
    fun leavingTheNoteSavesTheDraftBeforeTheBoardShows() = runComposeUiTest {
        val backend = ViewerTestBackend()
        mount(backend, identity = FixedIdentity(NOTEBOOK))
        onNodeWithTag(canvasViewerOptionTag(NOTEBOOK_NOTE_VIEWER_ID)).performClick()
        onNodeWithTag(MARKDOWN).performTextReplacement("typed before switching")

        onNodeWithTag(canvasViewerOptionTag(CanvasDocumentViewerId.CANVAS)).performClick()

        onNodeWithTag(BOARD).assertExists()
        runOnIdle { assertEquals("typed before switching", backend.source.markdown) }
    }

    @Test
    fun aDraftThatCannotSaveKeepsTheNoteOnShow() = runComposeUiTest {
        val backend = ViewerTestBackend()
        mount(backend, identity = FixedIdentity(NOTEBOOK))
        onNodeWithTag(canvasViewerOptionTag(NOTEBOOK_NOTE_VIEWER_ID)).performClick()
        onNodeWithTag(MARKDOWN).assertTextContains("original")
        onNodeWithTag(MARKDOWN).performTextReplacement("mine")
        runOnIdle { backend.source = backend.source.copy(markdown = "changed elsewhere") }

        onNodeWithTag(canvasViewerOptionTag(CanvasDocumentViewerId.CANVAS)).performClick()

        onNodeWithTag(MARKDOWN).assertExists()
        onNodeWithTag(BOARD).assertDoesNotExist()
        runOnIdle { assertEquals("changed elsewhere", backend.source.markdown) }
    }

    private fun ComposeUiTest.mount(backend: ViewerTestBackend, identity: CanvasNotebookIdentity) {
        val session = runBlocking {
            CanvasSession.create(
                store = InMemoryCanvasDocumentStore(),
                options = CanvasCreateOptions(agentId = "agent-1", canvasId = CANVAS, initialSceneJson = """{"elements":[]}"""),
            )
        }
        val registry = CanvasDocumentViewerRegistry().also { it.register(notebookNoteViewer(identity, backend)) }
        setContent {
            MaterialTheme {
                ProvideCanvasDocumentViewerRegistry(registry) {
                    CanvasDocumentViewerHost(session, Modifier.fillMaxSize()) { Text("Board", it.testTag(BOARD)) }
                }
            }
        }
    }

    private class FixedIdentity(private val notebook: NotebookDocumentId?) : CanvasNotebookIdentity {
        override suspend fun notebookOf(canvasId: CanvasId): NotebookDocumentId? = notebook.takeIf { canvasId == CANVAS }
        override suspend fun canvasOf(notebook: NotebookDocumentId): CanvasId? = CANVAS.takeIf { notebook == this.notebook }
    }

    private class ViewerTestBackend : NotebookNoteBackend {
        var source = NotebookNoteSnapshot(NOTEBOOK, "Plan", "original")
        val loaded = mutableListOf<NotebookDocumentId>()

        override suspend fun load(id: NotebookDocumentId): NotebookNoteLoad {
            loaded += id
            return if (id == source.id) NotebookNoteLoad.Found(source) else NotebookNoteLoad.NotFound
        }

        override suspend fun save(id: NotebookDocumentId, expected: String, replacement: String): NotebookNoteSave {
            if (source.markdown != expected) return NotebookNoteSave.Conflict(source)
            source = source.copy(markdown = replacement)
            return NotebookNoteSave.Saved(source)
        }

        override suspend fun flush(applied: NotebookNoteSnapshot): NotebookNoteSave = NotebookNoteSave.Saved(applied)
    }

    private companion object {
        val CANVAS = CanvasId("canvas-note-viewer-test")
        val NOTEBOOK = NotebookDocumentId("00112233445566778899aabbccddeeff")
        const val MARKDOWN = "notebook-note-markdown"
        const val BOARD = "note-viewer-board"
    }
}
