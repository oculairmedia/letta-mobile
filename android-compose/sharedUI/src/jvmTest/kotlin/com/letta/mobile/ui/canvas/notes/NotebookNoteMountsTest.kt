@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas.notes

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasNotebookIdentity
import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.notes.NotebookNoteBackend
import com.letta.mobile.data.canvas.notes.NotebookNoteLoad
import com.letta.mobile.data.canvas.notes.NotebookNoteSave
import com.letta.mobile.data.canvas.notes.NotebookNoteSnapshot
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerContext
import kotlin.test.Test
import kotlin.test.assertEquals

class NotebookNoteMountsTest {
    @Test
    fun remountReauthorizesARetainedDraftBeforeDisplayingIt() = runComposeUiTest {
        val shown = mutableStateOf(true)
        val backend = Backend()
        val viewer = notebookNoteViewer(Identity, backend)
        setContent {
            MaterialTheme {
                if (shown.value) viewer.render(Context, Modifier)
            }
        }
        onNodeWithTag(Markdown).performTextReplacement("private draft")
        runOnIdle { shown.value = false }
        runOnIdle { backend.allowed = false; shown.value = true }
        onNodeWithTag("notebook-note-denied").assertExists()
        runOnIdle { assertEquals(1, backend.storageReads) }
    }

    @Test
    fun disposingOneCleanMountDoesNotForkTheOtherMount() = runComposeUiTest {
        val first = mutableStateOf(true)
        val third = mutableStateOf(false)
        val viewer = notebookNoteViewer(Identity, Backend())
        setContent {
            MaterialTheme {
                Row {
                    if (first.value) viewer.render(Context, Modifier.weight(1f))
                    viewer.render(Context, Modifier.weight(1f))
                    if (third.value) viewer.render(Context, Modifier.weight(1f))
                }
            }
        }
        waitUntil { onAllNodesWithTag(Markdown).fetchSemanticsNodes().size == 2 }
        runOnIdle { first.value = false }
        runOnIdle { third.value = true }
        onAllNodesWithTag(Markdown)[0].performTextReplacement("same draft")
        onAllNodesWithTag(Markdown)[1].assertTextContains("same draft")
    }

    @Test
    fun deniedShellNoteReadsNoStorage() = shellFallback(false, Canvas, "notebook-note-denied")

    @Test
    fun unknownShellNoteUsesTheExistingMissingState() = shellFallback(true, CanvasId("unknown"), NOTEBOOK_NOTE_MISSING_TAG)

    private fun shellFallback(allowed: Boolean, canvas: CanvasId, tag: String) = runComposeUiTest {
        val backend = Backend().also { it.allowed = allowed }
        val registry = notebookCanvasViewers(Identity, backend)
        setContent {
            MaterialTheme {
                com.letta.mobile.ui.canvas.plugin.ProvideCanvasDocumentViewerRegistry(registry) {
                    com.letta.mobile.ui.shell.workspace.ShellNoteModule(canvas, Modifier)
                }
            }
        }
        onNodeWithTag(tag).assertExists()
        onNodeWithTag(Markdown).assertDoesNotExist()
        runOnIdle { assertEquals(0, backend.storageReads) }
    }

    private class Backend : NotebookNoteBackend {
        var allowed = true
        var storageReads = 0
        override suspend fun load(id: NotebookDocumentId): NotebookNoteLoad {
            if (!allowed) return NotebookNoteLoad.Denied
            storageReads++
            return NotebookNoteLoad.Found(NotebookNoteSnapshot(id, "Note", "original"))
        }
        override suspend fun save(id: NotebookDocumentId, expected: String, replacement: String): NotebookNoteSave =
            if (!allowed) NotebookNoteSave.Denied else NotebookNoteSave.Saved(NotebookNoteSnapshot(id, "Note", replacement))
        override suspend fun flush(applied: NotebookNoteSnapshot): NotebookNoteSave =
            if (!allowed) NotebookNoteSave.Denied else NotebookNoteSave.Saved(applied)
    }

    private companion object {
        const val Markdown = "notebook-note-markdown"
        val Canvas = CanvasId("note-mounts")
        val Notebook = NotebookDocumentId("00112233445566778899aabbccddeeff")
        val Context = CanvasDocumentViewerContext(Canvas, null, null)
        val Identity = object : CanvasNotebookIdentity {
            override suspend fun notebookOf(canvasId: CanvasId): NotebookDocumentId? = Notebook.takeIf { canvasId == Canvas }
            override suspend fun canvasOf(notebook: NotebookDocumentId): CanvasId? = Canvas.takeIf { notebook == Notebook }
        }
    }
}
