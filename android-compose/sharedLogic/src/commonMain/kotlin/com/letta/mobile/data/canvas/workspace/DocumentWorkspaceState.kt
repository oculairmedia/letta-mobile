package com.letta.mobile.data.canvas.workspace

import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.notes.NotebookNoteSession
import com.letta.mobile.data.canvas.references.NotebookReferenceSession

/** Two presentations of the current notebook identity; changing presentation never closes it. */
enum class DocumentPresentation {
    /** The existing explicit-save Markdown editor. */
    NOTE,
    /** The existing bounded read-only reference preview. */
    REFERENCES,
}

/**
 * Existing sessions supplied by a trusted opener.
 * @property note The live editor permanently bound to this identity.
 * @property references The same identity's preview, or null when this capability is unsupported.
 */
data class DocumentWorkspaceSessions(
    val note: NotebookNoteSession,
    val references: NotebookReferenceSession?,
) {
    /** The editor's existing notebook identity, without conversion or aliasing. */
    val id: NotebookDocumentId get() = note.id
}

/** Authorization and backend availability are decided by the injected trusted owner. */
sealed interface DocumentWorkspaceOpen {
    /**
     * Sessions must both belong to the exact requested identity.
     * @property sessions Authorized existing sessions for the requested document.
     */
    data class Opened(val sessions: DocumentWorkspaceSessions) : DocumentWorkspaceOpen
    /** No access; the current document and draft must remain mounted. */
    data object Denied : DocumentWorkspaceOpen
    /** No usable backend/capability; no alternative storage path is selected. */
    data object Unavailable : DocumentWorkspaceOpen
}

/** No default authority: every open, including a preview click, goes through this suspend seam. */
fun interface DocumentWorkspaceOpener {
    /** Reauthorize [id] and return sessions for that exact identity, or refuse the open. */
    suspend fun open(id: NotebookDocumentId): DocumentWorkspaceOpen
}

/** Explicit navigation failures, independent of the note session's save diagnostics. */
enum class DocumentWorkspaceProblem {
    /** The trusted opener refused access. */
    DENIED,
    /** The source could not provide a usable editor. */
    UNAVAILABLE,
    /** An opener returned sessions belonging to a different identity. */
    IDENTITY_MISMATCH,
    /** The existing note durability barrier refused navigation or close. */
    SAVE_FAILED,
}

/**
 * One mounted identity and its presentation. A pending request never replaces [current].
 * @property current Sessions retained until replacement or close has been acknowledged.
 * @property presentation The selected view of the current identity.
 * @property transitioning An opener, initial load or durability barrier is pending.
 * @property canGoBack A previous identity is available for a fresh authorized open.
 * @property problem The latest transition's explicit failure, if any.
 */
data class DocumentWorkspaceState(
    val current: DocumentWorkspaceSessions? = null,
    val presentation: DocumentPresentation = DocumentPresentation.NOTE,
    val transitioning: Boolean = false,
    val canGoBack: Boolean = false,
    val problem: DocumentWorkspaceProblem? = null,
)
