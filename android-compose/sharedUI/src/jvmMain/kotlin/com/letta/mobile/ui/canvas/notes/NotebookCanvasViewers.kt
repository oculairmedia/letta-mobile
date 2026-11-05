package com.letta.mobile.ui.canvas.notes

import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.canvas.notes.LocalNotebookNoteBackend
import com.letta.mobile.data.canvas.notes.NotebookCanvasIdentity
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerRegistry

/** The views a host whose canvases are all [notebooks] offers: the core ones, and each canvas's note. */
fun notebookCanvasViewers(notebooks: NotebookLocalStore): CanvasDocumentViewerRegistry =
    notebookCanvasViewers(NotebookCanvasIdentity(notebooks), LocalNotebookNoteBackend(notebooks))
