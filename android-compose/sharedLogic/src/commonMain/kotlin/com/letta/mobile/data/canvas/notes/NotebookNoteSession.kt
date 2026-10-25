package com.letta.mobile.data.canvas.notes

import com.letta.mobile.data.canvas.NotebookDocumentId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Explicit-save editor permanently bound to [id]. Create a separate session when switching notes.
 * Call edits and suspend operations on one owner dispatcher; backend I/O may change dispatchers.
 * No scope, polling job or independent lifecycle is created here. The owner's coroutine runs and
 * cancels operations; disposal itself never silently saves or discards. Hosts await [prepareClose]
 * before removing a dirty editor. A local CAS is not a distributed lock: later Automerge merges
 * can change the source and are discovered only by explicit refresh/save/flush.
 */
class NotebookNoteSession(val id: NotebookDocumentId, private val backend: NotebookNoteBackend) {
    private val mutableState = MutableStateFlow(NotebookNoteState(id))
    /** Observable state for this document alone. */
    val state: StateFlow<NotebookNoteState> = mutableState.asStateFlow()
    private val operations = Mutex()
    private var editGeneration = 0L

    /** Retain edits even while an operation is suspended. */
    fun edit(markdown: String) {
        editGeneration++
        mutableState.value = state.value.copy(draft = markdown)
    }

    /** Refresh the source without discarding edits, conflicts or pending durability. */
    suspend fun refresh() = operations.withLock { read(discard = false) }

    /**
     * Explicitly discard the captured draft and load current source content. Pending local history
     * must first reach storage; failure refuses discard. Edits made during this operation survive.
     */
    suspend fun discardAndReload(): Boolean = operations.withLock {
        val generation = editGeneration
        if (state.value.pendingDurability) flushPending()
        if (state.value.pendingDurability || generation != editGeneration) return@withLock false
        read(discard = true)
    }

    /** Save the captured draft, or retry only the outstanding durability barrier. */
    suspend fun save(): Boolean = operations.withLock { saveDraft() }

    /** Retry persistence without reapplying an already accepted mutation. */
    suspend fun retryPersistence(): Boolean = operations.withLock {
        flushPending()
        !state.value.pendingDurability && state.value.error == null
    }

    /**
     * Flush/save once and acknowledge close only if no draft, conflict, failure or pending history
     * remains. Concurrent edits keep the editor open; the owner must keep the session mounted on false.
     */
    suspend fun prepareClose(): Boolean = operations.withLock {
        if (state.value.pendingDurability) flushPending()
        if (state.value.pendingDurability) return@withLock false
        if (state.value.dirty) saveDraft()
        val current = state.value
        current.baseline != null && !current.dirty && !current.conflict && current.error == null && !current.notFound
    }

    private suspend fun read(discard: Boolean): Boolean {
        val generation = editGeneration
        val before = state.value
        mutableState.value = before.copy(loading = true, error = null)
        try {
            acceptLoad(backend.load(id), before, generation, discard)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expectedFailure: Exception) {
            mutableState.value = state.value.copy(error = expectedFailure.message ?: "Notebook read failed")
        } finally {
            mutableState.value = state.value.copy(loading = false)
        }
        return !state.value.notFound && state.value.error == null
    }

    private fun acceptLoad(result: NotebookNoteLoad, before: NotebookNoteState, generation: Long, discard: Boolean) {
        when (result) {
            is NotebookNoteLoad.Found -> acceptSnapshot(result.snapshot, mayReplaceDraft(before, generation, discard))
            NotebookNoteLoad.NotFound -> mutableState.value = state.value.copy(notFound = true)
            is NotebookNoteLoad.Unavailable -> mutableState.value = state.value.copy(error = result.reason)
        }
    }

    private fun mayReplaceDraft(before: NotebookNoteState, generation: Long, discard: Boolean): Boolean =
        generation == editGeneration && !state.value.pendingDurability && (discard || !before.dirty && !before.conflict)

    private fun acceptSnapshot(snapshot: NotebookNoteSnapshot, mayReplace: Boolean) {
        val current = state.value
        mutableState.value = current.copy(
            baseline = if (mayReplace || current.baseline == null) snapshot else current.baseline,
            observed = snapshot,
            draft = if (mayReplace) snapshot.markdown else current.draft,
            notFound = false,
        )
    }

    private suspend fun saveDraft(): Boolean {
        val before = state.value
        val baseline = before.baseline
        return when {
            before.pendingDurability -> flushPending()
            baseline == null || !before.canSave -> false
            !before.dirty -> before.error == null
            else -> writing { backend.save(id, baseline.markdown, before.draft) }
        }
    }

    private suspend fun flushPending(): Boolean {
        val current = state.value
        val applied = current.applied ?: return current.error == null
        if (!current.pendingDurability) return current.error == null
        return writing { backend.flush(applied) }
    }

    private suspend fun writing(action: suspend () -> NotebookNoteSave): Boolean {
        mutableState.value = state.value.copy(saving = true, error = null)
        try {
            accept(action())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expectedFailure: Exception) {
            mutableState.value = state.value.copy(error = expectedFailure.message ?: "Notebook save failed")
        } finally {
            mutableState.value = state.value.copy(saving = false)
        }
        return !state.value.dirty && !state.value.conflict && state.value.error == null
    }

    private fun accept(result: NotebookNoteSave) {
        val current = state.value
        mutableState.value = when (result) {
            is NotebookNoteSave.Saved -> current.copy(
                baseline = result.snapshot, applied = result.snapshot, durable = result.snapshot,
                observed = result.snapshot, notFound = false,
            )
            is NotebookNoteSave.PendingDurability -> current.copy(
                baseline = result.applied, applied = result.applied, error = result.reason,
                observed = if (current.applied == result.applied) current.observed else result.applied,
            )
            is NotebookNoteSave.Conflict -> current.copy(
                baseline = result.durable ?: current.baseline, applied = result.durable ?: current.applied,
                observed = result.current, durable = result.durable ?: current.durable,
            )
            is NotebookNoteSave.Unavailable -> current.copy(error = result.reason)
        }
    }
}
