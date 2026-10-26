package com.letta.mobile.data.canvas.notes

import com.letta.mobile.data.canvas.NotebookDocumentId

/**
 * One document's editor state. [baseline] is the CAS base, [applied] the last acknowledged local
 * mutation, and [durable] the last storage acknowledgement. A read alone never acknowledges a
 * pending mutation. [observed] retains newer source content alongside the user's [draft].
 * [indeterminateWrite] records a submitted replacement requiring reconciliation; even undoing
 * to the old baseline cannot make that state clean until a read/flush acknowledges current state.
 * @property indeterminateWrite Submitted text retained until reconciliation flush succeeds without a conflict.
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
    val indeterminateWrite: NotebookNoteSnapshot? = null,
) {
    /** Editing or pending persistence prevents close acknowledgement. */
    val dirty: Boolean get() = baseline?.markdown?.let { draft != it } == true || pendingDurability || indeterminateWrite != null
    /** The applied local mutation has not yet reached a storage barrier. */
    val pendingDurability: Boolean get() = applied != null && applied != durable
    /** Source Markdown changed relative to the editor's CAS base. */
    val conflict: Boolean get() = baseline != null && observed != null && baseline.markdown != observed.markdown
    /** A stale source must be explicitly reloaded/discarded before another replacement. */
    val canSave: Boolean get() = baseline != null && !loading && !saving && !conflict && !notFound
}

/** Reduce a read against the captured edit state without acknowledging pending writes. */
internal fun NotebookNoteState.afterLoad(
    result: NotebookNoteLoad,
    previous: NotebookNoteState,
    discard: Boolean,
    editsUnchanged: Boolean,
): NotebookNoteState = when (result) {
    is NotebookNoteLoad.Found -> {
        val mayReplace = canAcceptLoadedSnapshot(previous, discard, editsUnchanged)
        copy(
            baseline = if (mayReplace || baseline == null) result.snapshot else baseline,
            observed = result.snapshot,
            draft = if (mayReplace) result.snapshot.markdown else draft,
            notFound = false,
        )
    }
    NotebookNoteLoad.NotFound -> copy(notFound = true)
    is NotebookNoteLoad.Unavailable -> copy(error = result.reason)
}

/** Reduce an acknowledged save or flush while retaining edits made during backend I/O. */
internal fun NotebookNoteState.afterSave(result: NotebookNoteSave): NotebookNoteState = when (result) {
    is NotebookNoteSave.Saved -> acknowledge(result.snapshot).copy(observed = result.snapshot, notFound = false)
    is NotebookNoteSave.PendingDurability -> copy(
        baseline = result.applied, applied = result.applied, error = result.reason,
        observed = if (applied == result.applied) observed else result.applied,
    )
    is NotebookNoteSave.Conflict -> (result.durable?.let { acknowledge(it) } ?: this).copy(observed = result.current)
    is NotebookNoteSave.Unavailable -> copy(error = result.reason)
}

/** A reconciliation barrier may acknowledge history without resolving the current source. */
internal fun NotebookNoteState.afterReconciliation(
    result: NotebookNoteSave,
    attempted: NotebookNoteSnapshot,
): NotebookNoteState {
    val durable = when (result) {
        is NotebookNoteSave.Saved -> result.snapshot
        is NotebookNoteSave.Conflict -> result.durable
            ?: return copy(error = "Notebook write outcome unknown; retry persistence")
        is NotebookNoteSave.PendingDurability -> return copy(error = result.reason)
        is NotebookNoteSave.Unavailable -> return copy(error = result.reason)
    }
    // Only submitted text or the old baseline can resolve our CAS base automatically.
    // Unrelated peer text remains an explicit conflict with the retained draft.
    val casBaseline = if (durable.markdown == attempted.markdown || durable.markdown == baseline?.markdown) {
        durable
    } else {
        baseline
    }
    return afterSave(result).copy(
        baseline = casBaseline,
        // A conflict acknowledges older history; current state still needs a successful flush.
        indeterminateWrite = if (result is NotebookNoteSave.Saved) null else attempted,
        notFound = false,
    )
}

private fun NotebookNoteState.acknowledge(snapshot: NotebookNoteSnapshot): NotebookNoteState =
    copy(baseline = snapshot, applied = snapshot, durable = snapshot)

private fun NotebookNoteState.canAcceptLoadedSnapshot(
    previous: NotebookNoteState,
    discard: Boolean,
    editsUnchanged: Boolean,
): Boolean {
    val hasNoPendingWrite = !pendingDurability && indeterminateWrite == null
    val mayReplaceDraft = discard || (!previous.dirty && !previous.conflict)
    return editsUnchanged && hasNoPendingWrite && mayReplaceDraft
}
