package com.letta.mobile.data.canvas

import com.letta.mobile.data.model.CanvasId

/**
 * One document, two names: a notebook-backed canvas's [CanvasId] and
 * the [NotebookDocumentId] its note and references use name the same notebook CRDT. Views over that
 * document resolve one from the other here instead of keeping a copy. A store whose canvases are not
 * notebooks (web's key-value store, the host's relay log) does not implement it.
 */
interface CanvasNotebookIdentity {
    /** The notebook that holds [canvasId], or null when no notebook does. */
    suspend fun notebookOf(canvasId: CanvasId): NotebookDocumentId?

    /** The canvas a notebook carries, or null for an unknown or canvas-less notebook. */
    suspend fun canvasOf(notebook: NotebookDocumentId): CanvasId?
}
