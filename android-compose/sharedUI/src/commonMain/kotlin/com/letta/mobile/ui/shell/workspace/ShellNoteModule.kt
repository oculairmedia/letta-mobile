package com.letta.mobile.ui.shell.workspace

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.ui.canvas.notes.NOTEBOOK_NOTE_VIEWER_ID
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerContext
import com.letta.mobile.ui.canvas.plugin.LocalCanvasDocumentViewers
import com.letta.mobile.ui.shell.modules.UnavailableModule

/** The host's existing note view, without mounting a board or acquiring its geometry/session. */
@Composable
internal fun ShellNoteModule(board: CanvasId?, modifier: Modifier) {
    val viewers = LocalCanvasDocumentViewers.current
    val note = viewers.all.firstOrNull { it.id == NOTEBOOK_NOTE_VIEWER_ID }
    if (board == null || note == null) {
        UnavailableModule(modifier)
    } else {
        // Both call sites use this registered renderer and its window-owned retained note table.
        note.render(CanvasDocumentViewerContext(board, revision = null, sceneJson = null), modifier)
    }
}
