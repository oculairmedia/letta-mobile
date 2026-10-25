package com.letta.mobile.data.canvas.notes

import com.letta.mobile.data.canvas.NotebookDocumentId

/** Markdown and title observed together in one existing notebook document. */
data class NotebookNoteSnapshot(val id: NotebookDocumentId, val title: String, val markdown: String)

/** Reads never turn absence or failure into a successfully loaded empty note. */
sealed interface NotebookNoteLoad {
    /** A document exists, including when its Markdown is empty. */
    data class Found(val snapshot: NotebookNoteSnapshot) : NotebookNoteLoad
    /** The local repository does not have this document. */
    data object NotFound : NotebookNoteLoad
    /** The source could not be read; [reason] describes that source failure. */
    data class Unavailable(val reason: String) : NotebookNoteLoad
}

/**
 * Local-owned notebook operations, with no remote grant authority. Hosts must establish trusted
 * access separately. Cancellation propagates; an interrupted write may already have been applied.
 */
interface NotebookNoteBackend {
    /** Read [id] without creating a document or starting background observation. */
    suspend fun load(id: NotebookDocumentId): NotebookNoteLoad

    /** Compare and splice Markdown atomically, then await storage acknowledgement. */
    suspend fun save(id: NotebookDocumentId, expected: String, replacement: String): NotebookNoteSave

    /**
     * Flush the previously [applied] mutation without applying it again. If newer peer content is
     * observed, report it as a conflict while retaining acknowledgement of the applied history.
     */
    suspend fun flush(applied: NotebookNoteSnapshot): NotebookNoteSave
}
