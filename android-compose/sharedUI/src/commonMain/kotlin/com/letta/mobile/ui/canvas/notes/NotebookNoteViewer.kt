package com.letta.mobile.ui.canvas.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.canvas.CanvasNotebookIdentity
import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.notes.NotebookNoteBackend
import com.letta.mobile.data.canvas.notes.NotebookNoteSession
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.canvas_viewer_note_failed
import com.letta.mobile.sharedui.resources.canvas_viewer_note_missing
import com.letta.mobile.sharedui.resources.canvas_viewer_retry
import com.letta.mobile.sharedui.resources.notebook_note_title
import com.letta.mobile.sharedui.resources.document_workspace_denied
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewer
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerId
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerRegistry
import com.letta.mobile.ui.canvas.plugin.CanvasViewerLeaveCheck
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaSpinner
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource

/** The note view of a notebook-backed canvas. */
val NOTEBOOK_NOTE_VIEWER_ID = CanvasDocumentViewerId("note")

internal const val NOTEBOOK_NOTE_MISSING_TAG = "notebook-note-viewer-missing"
internal const val NOTEBOOK_NOTE_FAILED_TAG = "notebook-note-viewer-failed"

/**
 * The canvas's own note, edited in place: the same notebook document the board draws, resolved
 * through [identity], never a copy. Leaving the view saves the draft first and stays when a
 * conflict or a failed write remains; a note with nothing unsaved (one that never loaded) just
 * leaves. A canvas's note session with unsaved work lives as long as this viewer, keyed by the
 * canvas, so the draft survives its view being unmounted (the pane closing, another canvas opening)
 * and a compaction that gives the notebook a new ID; a clean session is released with its view.
 * Quitting saves every retained draft first. A host registers it only where its canvases are notebooks.
 */
fun notebookNoteViewer(identity: CanvasNotebookIdentity, backend: NotebookNoteBackend): CanvasDocumentViewer {
    val sessions = NotebookNoteSessions(backend)
    return CanvasDocumentViewer(
        id = NOTEBOOK_NOTE_VIEWER_ID,
        label = { stringResource(Res.string.notebook_note_title) },
        render = { context, modifier -> NotebookNoteView(context.canvasId, identity, sessions, modifier) },
        // Every retained note, shown or not: each dirty one saves; any that cannot holds the quit.
        prepareClose = sessions::prepareClose,
    )
}

/** The views a host whose canvases are all notebooks offers: the core ones, and each canvas's note. */
fun notebookCanvasViewers(identity: CanvasNotebookIdentity, backend: NotebookNoteBackend): CanvasDocumentViewerRegistry =
    CanvasDocumentViewerRegistry().also { it.register(notebookNoteViewer(identity, backend)) }

private sealed interface NoteLookup {
    data object Resolving : NoteLookup
    data object Missing : NoteLookup
    data object Failed : NoteLookup
    data class Found(val notebook: NotebookDocumentId) : NoteLookup
}

@Composable
private fun NotebookNoteView(
    canvasId: CanvasId,
    identity: CanvasNotebookIdentity,
    sessions: NotebookNoteSessions,
    modifier: Modifier,
) {
    var attempt by remember(canvasId) { mutableIntStateOf(0) }
    val lookup by produceState<NoteLookup>(NoteLookup.Resolving, canvasId, identity, attempt) {
        value = NoteLookup.Resolving
        value = try {
            identity.notebookOf(canvasId)?.let(NoteLookup::Found) ?: NoteLookup.Missing
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NoteLookup.Failed
        }
    }
    when (val found = lookup) {
        NoteLookup.Resolving -> Centered(modifier) { LettaSpinner(size = LettaDimens.Control.spinnerLg) }
        NoteLookup.Missing -> Centered(modifier) {
            Text(stringResource(Res.string.canvas_viewer_note_missing), Modifier.testTag(NOTEBOOK_NOTE_MISSING_TAG))
        }
        NoteLookup.Failed -> Centered(modifier) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
                Text(stringResource(Res.string.canvas_viewer_note_failed), Modifier.testTag(NOTEBOOK_NOTE_FAILED_TAG))
                TextButton(onClick = { attempt++ }) { Text(stringResource(Res.string.canvas_viewer_retry)) }
            }
        }
        is NoteLookup.Found -> {
            val session = remember(canvasId, found.notebook, sessions) { sessions.acquire(canvasId, found.notebook) }
            DisposableEffect(session) { onDispose { sessions.release(canvasId, session) } }
            CanvasViewerLeaveCheck { !session.state.value.dirty || session.prepareClose() }
            AuthorizedNote(session, modifier)
        }
    }
}

@Composable
private fun AuthorizedNote(session: NotebookNoteSession, modifier: Modifier) {
    // A retained draft is not a retained grant. Hide it until this mount has reauthorized.
    val checked by produceState<NoteLookup>(NoteLookup.Resolving, session) {
        session.refresh()
        value = if (session.state.value.error != null) NoteLookup.Failed else NoteLookup.Found(session.id)
    }
    val state by session.state.collectAsState()
    when {
        checked == NoteLookup.Resolving -> Centered(modifier) { LettaSpinner(size = LettaDimens.Control.spinnerLg) }
        state.denied -> Centered(modifier) {
            Text(stringResource(Res.string.document_workspace_denied), Modifier.testTag("notebook-note-denied"))
        }
        checked == NoteLookup.Failed -> Centered(modifier) {
            Text(stringResource(Res.string.canvas_viewer_note_failed), Modifier.testTag(NOTEBOOK_NOTE_FAILED_TAG))
        }
        else -> NotebookNoteEditorContent(session, modifier.fillMaxSize())
    }
}

@Composable
private fun Centered(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
