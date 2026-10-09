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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The read-only file viewer (letta-mobile-bzvro.26): which file is open and what it holds. */
@Immutable
data class WorkspaceFileViewerState(
    val path: String? = null,
    val loading: Boolean = false,
    val content: WorkspaceFileContent? = null,
    val error: String? = null,
) {
    val isOpen: Boolean get() = path != null
}

/** What the shared file viewer can ask of its controller. */
interface WorkspaceFileViewerActions {
    fun close()

    fun retry()
}

/** Opens a workspace file by path; hosts hand [open] to tool cards and other path affordances. */
fun interface WorkspaceFileOpener {
    fun open(path: String)
}

/** Pure transitions of [WorkspaceFileViewerState]; a read for a file no longer open is dropped. */
object WorkspaceFileViewerReducer {
    fun opening(path: String): WorkspaceFileViewerState = WorkspaceFileViewerState(path = path, loading = true)

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

    /** The open file's memory agent, when it is read from that agent's MemFS (see [open]). */
    private var memoryAgentId: String? = null

    /**
     * Opens [path]; a relative one resolves against [cwd]. With no [cwd], a relative path is
     * [memoryAgentId]'s memory file (letta-mobile-bzvro.37: a memory tool's `system/human/…`),
     * never a path on the host's own working directory.
     */
    fun open(path: String, cwd: String?, memoryAgentId: String? = null) {
        val inMemory = cwd.isNullOrBlank() && !WorkspacePaths.isAbsolute(path) && !memoryAgentId.isNullOrBlank()
        this.memoryAgentId = memoryAgentId.takeIf { inMemory }
        load(if (inMemory) path.removePrefix("./") else WorkspacePaths.resolve(path, cwd))
    }

    override fun retry() {
        stateFlow.value.path?.let(::load)
    }

    override fun close() {
        readJob?.cancel()
        memoryAgentId = null
        stateFlow.value = WorkspaceFileViewerState()
    }

    private fun load(path: String) {
        readJob?.cancel()
        stateFlow.value = WorkspaceFileViewerReducer.opening(path)
        val agentId = memoryAgentId
        readJob = scope.launch {
            try {
                val content = if (agentId != null) source.readMemory(agentId, path) else source.read(path)
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

/** The file a tool call works on, read from its arguments (`file_path`, `path`, …), or null. */
object ToolFileTargets {
    private val PATH_KEYS = listOf("file_path", "filePath", "notebook_path", "target_file", "path")
    private val json = Json { ignoreUnknownKeys = true }

    fun pathOf(arguments: String): String? {
        if (arguments.isBlank()) return null
        val obj = runCatching { json.parseToJsonElement(arguments) as? JsonObject }.getOrNull() ?: return null
        return PATH_KEYS.firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() && '\n' !in it }
        }
    }
}
