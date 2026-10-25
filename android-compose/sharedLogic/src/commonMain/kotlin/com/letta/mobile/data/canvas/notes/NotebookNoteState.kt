package com.letta.mobile.data.canvas.notes

import com.letta.mobile.data.canvas.NotebookDocumentId

/**
 * One document's editor state. [baseline] is the CAS base, [applied] the last acknowledged local
 * mutation, and [durable] the last storage acknowledgement. A read alone never acknowledges a
 * pending mutation. [observed] retains newer source content alongside the user's [draft].
 */
data class NotebookNoteState(
    val id: NotebookDocumentId,
    val baseline: NotebookNoteSnapshot? = null,
    val applied: NotebookNoteSnapshot? = null,
    val durable: NotebookNoteSnapshot? = null,
    val observed: NotebookNoteSnapshot? = null,
    val draft: String = "",
    val loading: Boolean = false,
    val saving: Boolean = false,
    val notFound: Boolean = false,
    val error: String? = null,
) {
    /** Editing or pending persistence prevents close acknowledgement. */
    val dirty: Boolean get() = baseline?.markdown?.let { draft != it } == true || pendingDurability
    /** The applied local mutation has not yet reached a storage barrier. */
    val pendingDurability: Boolean get() = applied != null && applied != durable
    /** Source Markdown changed relative to the editor's CAS base. */
    val conflict: Boolean get() = baseline != null && observed != null && baseline.markdown != observed.markdown
    /** A stale source must be explicitly reloaded/discarded before another replacement. */
    val canSave: Boolean get() = baseline != null && !loading && !saving && !conflict && !notFound
}
