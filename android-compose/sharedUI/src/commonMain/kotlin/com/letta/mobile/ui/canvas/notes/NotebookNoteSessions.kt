package com.letta.mobile.ui.canvas.notes

import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.notes.NotebookNoteBackend
import com.letta.mobile.data.canvas.notes.NotebookNoteSession
import com.letta.mobile.data.model.CanvasId

/** Window-owned holds on notes; dirty drafts survive the last view and participate in quit. */
internal class NotebookNoteSessions(private val backend: NotebookNoteBackend) {
    private val held = mutableMapOf<CanvasId, Pair<NotebookNoteSession, Int>>()

    fun acquire(canvasId: CanvasId, notebook: NotebookDocumentId): NotebookNoteSession {
        val entry = held[canvasId]
        val session = entry?.first ?: NotebookNoteSession(notebook, backend)
        held[canvasId] = session to ((entry?.second ?: 0) + 1)
        return session
    }

    fun release(canvasId: CanvasId, session: NotebookNoteSession) {
        val entry = held[canvasId]?.takeIf { it.first === session } ?: return
        val holds = entry.second - 1
        if (holds == 0 && !session.state.value.dirty) held.remove(canvasId)
        else held[canvasId] = session to holds
    }

    /**
     * Saves each retained draft, then checks again: a note edited while another was saving,
     * or opened meanwhile, is still unsaved and must hold the quit.
     */
    suspend fun prepareClose(): Boolean {
        val saved = held.values.map { it.first }.toList().map { !it.state.value.dirty || it.prepareClose() }.all { it }
        return saved && held.values.none { it.first.state.value.dirty }
    }
}
