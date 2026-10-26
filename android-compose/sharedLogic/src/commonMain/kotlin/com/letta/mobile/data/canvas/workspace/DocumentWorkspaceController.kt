package com.letta.mobile.data.canvas.workspace

import com.letta.mobile.data.canvas.NotebookDocumentId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared composition lifecycle over existing note/reference sessions. Call on one owner dispatcher.
 * The caller owns operation coroutines; newer transitions cancel and supersede older ones. A mutex
 * serializes opener/save/commit work; cancellation checks reject even cancellation-ignoring
 * openers. Owners must await [close] before unmounting; disposal never saves or discards a draft.
 */
class DocumentWorkspaceController(private val opener: DocumentWorkspaceOpener) {
    private val mutableState = MutableStateFlow(DocumentWorkspaceState())
    /** Current mounted document, capability and transition diagnostics. */
    val state: StateFlow<DocumentWorkspaceState> = mutableState.asStateFlow()
    private val transitions = Mutex()
    private val history = mutableListOf<NotebookDocumentId>()
    private var generation = 0L
    private var activeTransition: Job? = null

    /** Change only the presentation; the existing editor session and draft remain intact. */
    fun present(presentation: DocumentPresentation) {
        mutableState.value = state.value.copy(presentation = presentation)
    }

    /** Fresh trusted authorization, including opens requested by a read-only reference preview. */
    suspend fun open(id: NotebookDocumentId): Boolean = transition { navigate(id, back = false) }

    /** Reauthorize the previous identity; pop history only after a successful durable transition. */
    suspend fun back(): Boolean = transition {
        history.lastOrNull()?.let { navigate(it, back = true) } ?: false
    }

    /** Save/flush through the existing barrier; refusal leaves the current document mounted. */
    suspend fun close(): Boolean = transition {
        val current = state.value.current
        if (current != null && !current.note.prepareClose()) {
            checkedFailure(DocumentWorkspaceProblem.SAVE_FAILED)
        } else {
            currentCoroutineContext().ensureActive()
            current?.references?.cancel()
            history.clear()
            mutableState.value = state.value.copy(current = null, canGoBack = false, problem = null)
            true
        }
    }

    private suspend fun transition(action: suspend () -> Boolean): Boolean = coroutineScope {
        val request = ++generation
        activeTransition?.cancel()
        activeTransition = coroutineContext.job
        try {
            transitions.withLock {
                currentCoroutineContext().ensureActive()
                mutableState.value = state.value.copy(transitioning = true, problem = null)
                action()
            }
        } finally {
            if (request == generation) {
                activeTransition = null
                mutableState.value = state.value.copy(transitioning = false)
            }
        }
    }

    private suspend fun navigate(id: NotebookDocumentId, back: Boolean): Boolean {
        val result = try {
            opener.open(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DocumentWorkspaceOpen.Unavailable
        }
        return when (result) {
            DocumentWorkspaceOpen.Denied -> checkedFailure(DocumentWorkspaceProblem.DENIED)
            DocumentWorkspaceOpen.Unavailable -> checkedFailure(DocumentWorkspaceProblem.UNAVAILABLE)
            is DocumentWorkspaceOpen.Opened -> mount(id, result.sessions, back)
        }
    }

    private suspend fun mount(id: NotebookDocumentId, target: DocumentWorkspaceSessions, back: Boolean): Boolean {
        try {
            currentCoroutineContext().ensureActive()
            val current = state.value.current
            return when {
                target.id != id || target.references?.id?.let { it != id } == true -> fail(DocumentWorkspaceProblem.IDENTITY_MISMATCH)
                // A freshly authorized self-open must not replace the live draft with a new session.
                current?.id == id -> true
                !ready(target) -> fail(DocumentWorkspaceProblem.UNAVAILABLE)
                current != null && !current.note.prepareClose() -> checkedFailure(DocumentWorkspaceProblem.SAVE_FAILED)
                else -> commit(target, back)
            }
        } finally {
            if (target.references !== state.value.current?.references) target.references?.cancel()
        }
    }

    private suspend fun commit(target: DocumentWorkspaceSessions, back: Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val current = state.value.current
        current?.references?.cancel()
        if (back) history.removeAt(history.lastIndex) else if (current != null) history.add(current.id)
        mutableState.value = state.value.copy(current = target, canGoBack = history.isNotEmpty(), problem = null)
        return true
    }

    private suspend fun ready(target: DocumentWorkspaceSessions): Boolean {
        // References may be the first presentation; close must not depend on visiting Note first.
        if (target.note.state.value.baseline == null) target.note.refresh()
        currentCoroutineContext().ensureActive()
        val loaded = target.note.state.value
        return loaded.baseline != null && !loaded.loading && !loaded.notFound && loaded.error == null
    }

    private suspend fun checkedFailure(problem: DocumentWorkspaceProblem): Boolean {
        currentCoroutineContext().ensureActive()
        return fail(problem)
    }

    private fun fail(problem: DocumentWorkspaceProblem): Boolean {
        mutableState.value = state.value.copy(problem = problem)
        return false
    }
}
