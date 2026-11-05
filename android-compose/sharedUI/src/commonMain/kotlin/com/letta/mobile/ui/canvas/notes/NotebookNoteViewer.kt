package com.letta.mobile.ui.canvas.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.canvas.CanvasNotebookIdentity
import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.notes.NotebookNoteBackend
import com.letta.mobile.data.canvas.notes.NotebookNoteSession
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.canvas_viewer_note
import com.letta.mobile.sharedui.resources.canvas_viewer_note_missing
import com.letta.mobile.ui.canvas.CanvasViewerLeaveCheck
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewer
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerId
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaSpinner
import org.jetbrains.compose.resources.stringResource

/** The note view of a notebook-backed canvas. */
val NOTEBOOK_NOTE_VIEWER_ID = CanvasDocumentViewerId("note")

internal const val NOTEBOOK_NOTE_MISSING_TAG = "notebook-note-viewer-missing"

/**
 * The canvas's own note, edited in place: the same notebook document the board draws, resolved
 * through [identity], never a copy. Leaving the view saves the draft first and stays when a
 * conflict or a failed write remains. A host registers it only where its canvases are notebooks.
 */
fun notebookNoteViewer(identity: CanvasNotebookIdentity, backend: NotebookNoteBackend): CanvasDocumentViewer =
    CanvasDocumentViewer(
        id = NOTEBOOK_NOTE_VIEWER_ID,
        label = { stringResource(Res.string.canvas_viewer_note) },
        render = { context, modifier -> NotebookNoteView(context.canvasId, identity, backend, modifier) },
    )

private sealed interface NoteLookup {
    data object Resolving : NoteLookup
    data object Missing : NoteLookup
    data class Found(val notebook: NotebookDocumentId) : NoteLookup
}

@Composable
private fun NotebookNoteView(canvasId: CanvasId, identity: CanvasNotebookIdentity, backend: NotebookNoteBackend, modifier: Modifier) {
    val lookup by produceState<NoteLookup>(NoteLookup.Resolving, canvasId, identity) {
        value = identity.notebookOf(canvasId)?.let(NoteLookup::Found) ?: NoteLookup.Missing
    }
    when (val found = lookup) {
        NoteLookup.Resolving -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LettaSpinner(size = LettaDimens.Control.spinnerLg)
        }
        NoteLookup.Missing -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.canvas_viewer_note_missing), Modifier.testTag(NOTEBOOK_NOTE_MISSING_TAG))
        }
        is NoteLookup.Found -> {
            val session = remember(found.notebook, backend) { NotebookNoteSession(found.notebook, backend) }
            CanvasViewerLeaveCheck(session::prepareClose)
            NotebookNoteEditorContent(session, modifier.fillMaxSize())
        }
    }
}
