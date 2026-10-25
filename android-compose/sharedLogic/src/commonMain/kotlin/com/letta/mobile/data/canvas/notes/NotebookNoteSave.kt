package com.letta.mobile.data.canvas.notes

/** Mutation acceptance and durability are separate outcomes. */
sealed interface NotebookNoteSave {
    /** [snapshot] was applied and its history reached the local storage barrier. */
    data class Saved(val snapshot: NotebookNoteSnapshot) : NotebookNoteSave

    /**
     * [current] differs from the expected/applied Markdown. [durable] acknowledges a previous
     * local mutation's persisted history, when known; it does not replace either draft or current.
     */
    data class Conflict(val current: NotebookNoteSnapshot, val durable: NotebookNoteSnapshot? = null) : NotebookNoteSave

    /** [applied] is in memory; retry must flush it before attempting another replacement. */
    data class PendingDurability(val applied: NotebookNoteSnapshot, val reason: String) : NotebookNoteSave

    /** No mutation was acknowledged. A read-only, missing or failed source remains explicit. */
    data class Unavailable(val reason: String) : NotebookNoteSave
}
