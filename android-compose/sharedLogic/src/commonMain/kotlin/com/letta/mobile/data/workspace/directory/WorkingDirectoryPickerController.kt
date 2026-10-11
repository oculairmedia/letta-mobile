package com.letta.mobile.data.workspace.directory

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The worktrees letta-code made for one repository (its `.letta/worktrees` folders). */
@Immutable
data class WorktreeListState(
    val repository: String? = null,
    /** Absolute worktree folders, by name. */
    val paths: List<String> = emptyList(),
    val isLoading: Boolean = false,
)

/**
 * letta-mobile-zxk2n.6 (G32): what the working-directory picker draws. [folder] is the folder
 * being browsed and [folders] its sub-folder names; [currentCwd] the conversation's directory
 * when the picker opened. [createFolderUnavailableReason] is set where this host cannot create
 * folders, and the picker shows it on a disabled control rather than hiding the control.
 */
@Immutable
data class WorkingDirectoryPickerState(
    val isOpen: Boolean = false,
    val currentCwd: String? = null,
    val folder: String? = null,
    val folders: List<String> = emptyList(),
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val recents: List<String> = emptyList(),
    val createFolderUnavailableReason: String? = null,
    val isCreatingFolder: Boolean = false,
    /** Where [currentCwd] is, when it is inside a letta-code worktree: the "in worktree" banner. */
    val worktree: WorktreeLocation? = null,
    val worktrees: WorktreeListState = WorktreeListState(),
) {
    val canGoUp: Boolean get() = folder?.let(DirectoryPaths::parent) != null
    val canCreateFolder: Boolean
        get() = when {
            createFolderUnavailableReason != null || isCreatingFolder -> false
            else -> folder != null
        }
}

/** What the picker's controls do; [WorkingDirectoryPickerController] is the shared implementation. */
interface WorkingDirectoryPickerActions {
    /** Shows the sub-folders of the absolute folder [path] (a row, a recent, or a typed path). */
    fun browse(path: String)

    fun up()

    /** Creates [name] inside the browsed folder and moves into it. */
    fun createFolder(name: String)

    /** Uses [path] as the conversation's working directory from the next turn, and closes. */
    fun choose(path: String)

    fun dismiss()

    fun clearError()
}

/** Messages for what a host cannot do, shown on the disabled control. */
object WorkingDirectoryPickerGates {
    const val CREATE_FOLDER_UNAVAILABLE: String =
        "New folders can be made only when the App Server runs on this computer: " +
            "letta-code has no command to create a folder."

    const val NOT_ABSOLUTE: String = "Enter a full path, such as /home/me/project or C:\\Users\\me\\project."
}

/**
 * letta-mobile-zxk2n.6 (G32): the shared working-directory picker. Browses with a
 * [WorkspaceDirectorySource] (`list_in_directory`), creates folders with a [FolderCreator] where the
 * host has one, offers [RecentFolders], and lists the repository's letta-code worktrees. Choosing
 * hands the path to [onChosen], which the platform binds to `change_device_state` cwd (it applies
 * from the next turn); the picker never changes a directory itself.
 */
class WorkingDirectoryPickerController(
    private val source: WorkspaceDirectorySource,
    private val scope: CoroutineScope,
    private val onChosen: (String) -> Unit,
    private val recents: RecentFolders? = null,
    private val folderCreator: FolderCreator? = null,
) : WorkingDirectoryPickerActions {
    private val _state = MutableStateFlow(WorkingDirectoryPickerState())
    val state: StateFlow<WorkingDirectoryPickerState> = _state.asStateFlow()

    private var browseJob: Job? = null
    private var worktreesJob: Job? = null

    /** Opens on the conversation's [currentCwd] (when known), with its worktrees. */
    fun open(currentCwd: String?) {
        val cwd = currentCwd?.takeIf { it.isNotBlank() && DirectoryPaths.isAbsolute(it) }?.let(DirectoryPaths::normalize)
        _state.value = WorkingDirectoryPickerState(
            isOpen = true,
            currentCwd = cwd,
            recents = recents?.recent?.value.orEmpty(),
            createFolderUnavailableReason = if (folderCreator == null) WorkingDirectoryPickerGates.CREATE_FOLDER_UNAVAILABLE else null,
            worktree = WorktreePaths.locate(cwd),
        )
        if (cwd != null) {
            browse(cwd)
            loadWorktrees(WorktreePaths.repositoryOf(cwd))
        }
    }

    override fun browse(path: String) {
        if (!DirectoryPaths.isAbsolute(path)) {
            _state.update { it.copy(error = WorkingDirectoryPickerGates.NOT_ABSOLUTE) }
            return
        }
        val target = DirectoryPaths.normalize(path)
        browseJob?.cancel()
        _state.update { it.copy(folder = target, folders = emptyList(), hasMore = false, isLoading = true, error = null) }
        browseJob = scope.launch {
            val result = attempt { source.list(target) }
            _state.update { current ->
                if (!DirectoryPaths.same(current.folder, target)) return@update current
                result.fold(
                    onSuccess = { listing -> current.copy(folders = listing.folders, hasMore = listing.hasMore, isLoading = false) },
                    onFailure = { error -> current.copy(isLoading = false, error = messageOf(error)) },
                )
            }
            if (result.exceptionOrNull()?.message == DirectoryBrowseErrors.NOT_FOUND) forgetRecent(target)
        }
    }

    override fun up() {
        _state.value.folder?.let(DirectoryPaths::parent)?.let(::browse)
    }

    override fun createFolder(name: String) {
        val creator = folderCreator ?: return
        val parent = _state.value.folder ?: return
        if (!DirectoryPaths.isValidFolderName(name)) {
            _state.update { it.copy(error = DirectoryBrowseErrors.INVALID_NAME) }
            return
        }
        _state.update { it.copy(isCreatingFolder = true, error = null) }
        scope.launch {
            val created = attempt { creator.create(parent, name.trim()) }
            _state.update { it.copy(isCreatingFolder = false, error = created.exceptionOrNull()?.let(::messageOf)) }
            created.getOrNull()?.let(::browse)
        }
    }

    override fun choose(path: String) {
        if (!DirectoryPaths.isAbsolute(path)) {
            _state.update { it.copy(error = WorkingDirectoryPickerGates.NOT_ABSOLUTE) }
            return
        }
        val chosen = DirectoryPaths.normalize(path)
        recents?.record(chosen)
        dismiss()
        onChosen(chosen)
    }

    override fun dismiss() {
        browseJob?.cancel()
        worktreesJob?.cancel()
        _state.value = WorkingDirectoryPickerState()
    }

    override fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private fun loadWorktrees(repository: String) {
        worktreesJob?.cancel()
        _state.update { it.copy(worktrees = WorktreeListState(repository = repository, isLoading = true)) }
        worktreesJob = scope.launch {
            // No `.letta/worktrees` (or no browsing on this connection) is simply no worktrees.
            val paths = attempt { source.list(WorktreePaths.worktreesFolder(repository)) }
                .map { listing -> listing.folders.map { DirectoryPaths.child(listing.path, it) } }
                .getOrDefault(emptyList())
            _state.update { it.copy(worktrees = WorktreeListState(repository = repository, paths = paths)) }
        }
    }

    private fun forgetRecent(path: String) {
        val store = recents ?: return
        if (store.recent.value.none { DirectoryPaths.same(it, path) }) return
        store.forget(path)
        _state.update { it.copy(recents = store.recent.value) }
    }

    private fun messageOf(error: Throwable): String = when (error) {
        is DirectoryBrowseException -> error.message ?: DirectoryBrowseErrors.NOT_FOUND
        else -> error.message ?: "Something went wrong."
    }
}

/** [block]'s result or failure; cancellation still cancels. */
private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
    runCatching { block() }.onFailure { if (it is CancellationException) throw it }
