package com.letta.mobile.ui.shell.sidebar

import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.model.CanvasId

/**
 * A host's side pane of canvases (the desktop's): the board it shows and the verbs that change it.
 * The side menu opens boards into it, and a board mounted behind the chat closes the pane's copy
 * of it ([closeIfShowing], and `ProvideCanvasHostWithBoards`).
 */
interface ShellCanvasPane {
    /** The board the pane shows, or null while the pane is closed. */
    val activeSession: CanvasSession?

    /**
     * Shows [id] in the pane: the row menu's "Open in side panel" ([ShellAgentPanelActions.onOpenCanvasInPane]),
     * even in canvas mode, with the mount left as it is.
     */
    fun open(id: CanvasId)

    /** Closes the pane. */
    fun close()
}

/**
 * The panel's [ShellAgentPanelActions.onCanvasMounted] for this pane: a pick mounted on the board
 * behind the chat closes the pane if it shows that board, so it is not on screen twice.
 */
fun ShellCanvasPane.closeIfShowing(id: CanvasId) {
    if (activeSession?.canvasId == id) close()
}
