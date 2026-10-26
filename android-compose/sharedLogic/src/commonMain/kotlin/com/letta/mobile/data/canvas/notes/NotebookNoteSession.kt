package com.letta.mobile.data.canvas.notes

import com.letta.mobile.data.canvas.NotebookDocumentId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Explicit-save editor permanently bound to [id]. Create a separate session when switching notes.
 * Call edits and suspend operations on one owner dispatcher; backend I/O may change dispatchers.
 * No scope, polling job or independent lifecycle is created here. The owner's coroutine runs and
 * cancels operations; disposal itself never silently saves or discards. Hosts await [prepareClose]
 * before removing a dirty editor. A local CAS is not a distributed lock: later Automerge merges
 * can change the source and are discovered only by explicit refresh/save/flush.
 * Interrupted writes stay indeterminate until one cancellable read/flush attempt, bounded to
 * five seconds, acknowledges current state. Failure or a post-flush conflict retains the
 * reconciliation obligation and draft, and refuses close.
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
        if (!reconcileWrite()) return@withLock false
        flushPending()
        if (state.value.pendingDurability || generation != editGeneration) return@withLock false
        read(discard = true)
    }

    /** Save the captured draft, or retry only the outstanding durability barrier. */
    suspend fun save(): Boolean = operations.withLock { saveDraft() }

    /** Retry persistence without reapplying an already accepted mutation. */
    suspend fun retryPersistence(): Boolean = operations.withLock {
        if (!reconcileWrite()) return@withLock false
        flushPending()
        !state.value.pendingDurability && state.value.error == null
    }

    /**
     * Flush/save once and acknowledge close only if no draft, conflict, failure or pending history
     * remains. Concurrent edits keep the editor open; the owner must keep the session mounted on false.
     */
    suspend fun prepareClose(): Boolean = operations.withLock {
        if (!reconcileWrite()) return@withLock false
        flushPending()
        if (state.value.pendingDurability) return@withLock false
        if (state.value.dirty) saveDraft()
        val current = state.value
        current.canSave && !current.dirty && current.error == null
    }

    private suspend fun read(discard: Boolean): Boolean {
        val generation = editGeneration
        val before = state.value
        mutableState.value = before.copy(loading = true, error = null)
        try {
            val result = backend.load(id)
            mutableState.value = state.value.afterLoad(result, before, discard, generation == editGeneration)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expectedFailure: Exception) {
            mutableState.value = state.value.copy(error = expectedFailure.message ?: "Notebook read failed")
        } finally {
            mutableState.value = state.value.copy(loading = false)
        }
        return !state.value.notFound && state.value.error == null
    }

    private suspend fun saveDraft(): Boolean {
        if (!reconcileWrite()) return false
        val before = state.value
        val baseline = before.baseline
        return when {
            before.pendingDurability -> flushPending()
            baseline == null || !before.canSave -> false
            !before.dirty -> before.error == null
            else -> writing {
                mutableState.value = state.value.copy(indeterminateWrite = baseline.copy(markdown = before.draft))
                val result = backend.save(id, baseline.markdown, before.draft)
                if (result !is NotebookNoteSave.Unavailable) {
                    mutableState.value = state.value.copy(indeterminateWrite = null)
                }
                mutableState.value = state.value.afterSave(result)
            }
        }
    }

    private suspend fun reconcileWrite(): Boolean {
        val attempted = state.value.indeterminateWrite ?: return true
        writing(failureMessage = "Notebook write reconciliation failed") {
            val result = withTimeoutOrNull(5.seconds) {
                when (val loaded = backend.load(id)) {
                    is NotebookNoteLoad.Found -> backend.flush(loaded.snapshot)
                    NotebookNoteLoad.NotFound -> {
                        mutableState.value = state.value.copy(notFound = true)
                        NotebookNoteSave.Unavailable("Local notebook not found")
                    }
                    is NotebookNoteLoad.Unavailable -> NotebookNoteSave.Unavailable(loaded.reason)
                }
            } ?: NotebookNoteSave.Unavailable("Notebook write reconciliation timed out; retry persistence")
            mutableState.value = state.value.afterReconciliation(result, attempted)
        }
        return state.value.indeterminateWrite == null
    }

    private suspend fun flushPending(): Boolean {
        val current = state.value
        val applied = current.applied ?: return current.error == null
        if (!current.pendingDurability) return current.error == null
        return writing {
            val result = backend.flush(applied)
            mutableState.value = state.value.afterSave(result)
        }
    }

    private suspend fun writing(
        failureMessage: String = "Notebook save failed",
        action: suspend () -> Unit,
    ): Boolean {
        mutableState.value = state.value.copy(saving = true, error = null)
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expectedFailure: Exception) {
            mutableState.value = state.value.copy(error = expectedFailure.message ?: failureMessage)
        } finally {
            mutableState.value = state.value.copy(saving = false)
        }
        return !state.value.dirty && !state.value.conflict && state.value.error == null
    }
}
