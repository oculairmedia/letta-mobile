package com.letta.mobile.data.canvas.library

import com.letta.mobile.data.canvas.CanvasArchiveStore
import com.letta.mobile.data.canvas.CanvasConversationOptions
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasArchiveStore
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared canvas-library operations. Ordinary storage failures retain content and publish a retryable
 * error instead of escaping into the host's window scope. Cancellation and fatal errors propagate.
 * Retry reloads the library; it never repeats a potentially completed mutation.
 */
class CanvasLibraryController(
    private val store: CanvasDocumentStore,
    private val scope: CoroutineScope,
    private val archive: CanvasArchiveStore = InMemoryCanvasArchiveStore(),
    private val options: CanvasConversationOptions = CanvasConversationOptions(),
) {
    private val mutableState = MutableStateFlow(CanvasLibraryState())
    /** Loaded boards remain available while a refresh is pending or fails. */
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private val refreshPending = Mutex()

    /** Reloads storage; concurrent requests share the current refresh. Success clears the notice. */
    fun refresh() {
        if (!refreshPending.tryLock()) return
        execute("refresh", {
            store.listAll() to archive.archivedIds()
        }) { (documents, archived) ->
            mutableState.update { it.copy(documents = documents, archived = archived, error = null) }
        }.invokeOnCompletion { refreshPending.unlock() }
    }

    /** Archives or restores a board; a failure is visible and does not erase the loaded library. */
    fun setArchived(id: CanvasId, archived: Boolean) {
        execute("archive", {
            archive.setArchived(id, archived)
            archive.archivedIds()
        }) { ids -> mutableState.update { it.copy(archived = ids) } }
    }

    /** Opens a board only after storage succeeds and this controller's owning scope is still active. */
    fun open(id: CanvasId, onReady: (CanvasSession) -> Unit) {
        execute("open", { checkNotNull(CanvasSession.open(store, id, options)) { "Canvas is unavailable" } }, onReady)
    }

    /** Creates a board using the host's injected sync bindings; refresh retry never creates another. */
    fun createNew(agentId: String?, onReady: (CanvasSession) -> Unit) {
        execute("create", {
            val ordinal = store.listAll().size + 1
            CanvasSession.create(store, CanvasCreateOptions(
                title = "Canvas $ordinal",
                agentId = agentId,
                opLog = options.opLog,
                syncTransport = options.syncTransport,
                clock = options.clock,
            ))
        }) { session ->
            onReady(session)
            refresh()
        }
    }

    private fun <T> execute(operation: String, task: suspend () -> T, onSuccess: (T) -> Unit): Job = scope.launch {
        mutex.withLock {
            mutableState.update { it.copy(isLoading = true) }
            try {
                val result = runCatchingCancellable(task)
                currentCoroutineContext().ensureActive()
                result.fold(onSuccess = onSuccess, onFailure = { error ->
                    if (error !is Exception) throw error
                    Telemetry.error("CanvasLibrary", "operationFailed", error, "operation" to operation)
                    mutableState.update { it.copy(error = "Canvas storage is unavailable. Retry loading the library.") }
                })
            } finally {
                mutableState.update { it.copy(isLoading = false) }
            }
        }
    }
}
