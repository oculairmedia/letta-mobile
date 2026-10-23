package com.letta.mobile.data.workspace

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The read-only file viewer: which file is open and what it holds. */
@Immutable
data class WorkspaceFileViewerState(
    val path: String? = null,
    val loading: Boolean = false,
    val content: WorkspaceFileContent? = null,
    val error: String? = null,
    /** The lines (1-based, inclusive) the file was opened to show, highlighted and scrolled to. */
    val lines: IntRange? = null,
) {
    val isOpen: Boolean get() = path != null
}

/** What the shared file viewer can ask of its controller. */
interface WorkspaceFileViewerActions {
    fun close()

    fun retry()
}

/**
 * Opens a workspace file; hosts hand [open] to tool step chips, clickable paths and other path affordances.
 * A relative path needs a working directory. (A memory tool's entries open in the Memory view instead.)
 */
fun interface WorkspaceFileOpener {
    fun open(request: WorkspaceFileRequest)
}

/** A file to open: its [path] and the [lines] (1-based, inclusive) to show. */
@Immutable
data class WorkspaceFileRequest(val path: String, val lines: IntRange? = null)

/** Pure transitions of [WorkspaceFileViewerState]; a read for a file no longer open is dropped. */
object WorkspaceFileViewerReducer {
    fun opening(path: String, lines: IntRange? = null): WorkspaceFileViewerState =
        WorkspaceFileViewerState(path = path, loading = true, lines = lines)

    fun read(state: WorkspaceFileViewerState, content: WorkspaceFileContent): WorkspaceFileViewerState =
        if (state.path != content.path) state else state.copy(loading = false, content = content, error = null)

    fun failed(state: WorkspaceFileViewerState, path: String, message: String): WorkspaceFileViewerState =
        if (state.path != path) state else state.copy(loading = false, error = message)
}

class WorkspaceFileViewerController(
    private val source: WorkspaceFileSource,
    private val scope: CoroutineScope,
) : WorkspaceFileViewerActions, AutoCloseable {
    private val stateFlow = MutableStateFlow(WorkspaceFileViewerState())
    val state: StateFlow<WorkspaceFileViewerState> = stateFlow.asStateFlow()

    private var readJob: Job? = null

    /**
     * Opens [path]; a relative one resolves against [cwd]. With no [cwd] a relative path cannot be
     * opened, and is never read from the host's own working directory.
     */
    fun open(path: String, cwd: String?, lines: IntRange? = null) {
        load(WorkspacePaths.resolve(path, cwd), lines)
    }

    override fun retry() {
        val shown = stateFlow.value
        shown.path?.let { load(it, shown.lines) }
    }

    override fun close() {
        readJob?.cancel()
        stateFlow.value = WorkspaceFileViewerState()
    }

    private fun load(path: String, lines: IntRange?) {
        readJob?.cancel()
        stateFlow.value = WorkspaceFileViewerReducer.opening(path, lines)
        if (!WorkspacePaths.isAbsolute(path)) {
            stateFlow.update { WorkspaceFileViewerReducer.failed(it, path, WorkspaceFileErrors.NO_WORKING_DIRECTORY) }
            return
        }
        readJob = scope.launch {
            try {
                val content = source.read(path)
                stateFlow.update { WorkspaceFileViewerReducer.read(it, content) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = (error as? WorkspaceFileException)?.message ?: "The file could not be read."
                stateFlow.update { WorkspaceFileViewerReducer.failed(it, path, message) }
            }
        }
    }
}
