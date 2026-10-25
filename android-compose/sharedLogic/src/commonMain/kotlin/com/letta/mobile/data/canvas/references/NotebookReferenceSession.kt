package com.letta.mobile.data.canvas.references

import com.letta.mobile.data.canvas.NotebookDocumentId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job

/** Explicit lifecycle of a whole preview refresh; cancellation never masquerades as absence. */
enum class NotebookReferencePhase { IDLE, LOADING, READY, CANCELLED, FAILED }

/** One completed immutable tree, or an explicit pending/cancelled/failed refresh. */
data class NotebookReferenceState(
    val phase: NotebookReferencePhase = NotebookReferencePhase.IDLE,
    val root: NotebookReferenceNode? = null,
)

/**
 * Read-only preview permanently keyed by [id]. Call operations on one owner dispatcher. Each
 * explicit refresh reauthorizes all expanded targets and supersedes the prior refresh; old paint
 * data is cleared while access is checked again. The caller owns the coroutine and cancellation.
 * This session creates no independent scope, polling loop, editor or storage mutation.
 */
class NotebookReferenceSession(
    val id: NotebookDocumentId,
    private val resolver: NotebookReferenceResolver,
    private val limits: NotebookReferenceLimits = NotebookReferenceLimits(),
) {
    private val mutableState = MutableStateFlow(NotebookReferenceState())
    /** State for this identity alone. */
    val state: StateFlow<NotebookReferenceState> = mutableState.asStateFlow()
    private var generation = 0L
    private var activeRefresh: Job? = null

    /** Rebuild the bounded tree; propagate cancellation and reject obsolete results. */
    suspend fun refresh(): Unit = coroutineScope {
        val request = ++generation
        activeRefresh?.cancel()
        activeRefresh = coroutineContext.job
        mutableState.value = NotebookReferenceState(NotebookReferencePhase.LOADING)
        try {
            val root = buildNotebookReferenceTree(id, resolver, limits)
            if (request == generation) mutableState.value = NotebookReferenceState(NotebookReferencePhase.READY, root)
        } catch (cancelled: CancellationException) {
            if (request == generation) mutableState.value = NotebookReferenceState(NotebookReferencePhase.CANCELLED)
            throw cancelled
        } catch (_: Exception) {
            if (request == generation) mutableState.value = NotebookReferenceState(NotebookReferencePhase.FAILED)
        } finally {
            if (request == generation) activeRefresh = null
        }
    }

    /** Cancel pending work and withdraw any prior tree, without swallowing caller cancellation. */
    fun cancel() {
        generation++
        activeRefresh?.cancel()
        activeRefresh = null
        mutableState.value = NotebookReferenceState(NotebookReferencePhase.CANCELLED)
    }
}
