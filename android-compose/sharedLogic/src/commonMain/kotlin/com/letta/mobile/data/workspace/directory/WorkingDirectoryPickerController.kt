package com.letta.mobile.data.workspace.directory

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.runtime.AgentDefaultsRepository
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
    /** The agent "Make default for this agent" would save for; null hides that control. */
    val agentId: AgentId? = null,
) {
    val canGoUp: Boolean get() = devicePathOrNull(folder)?.parent != null
    val canCreateFolder: Boolean
        get() = when {
            createFolderUnavailableReason != null || isCreatingFolder -> false
            else -> folder != null
        }

    val canMakeDefault: Boolean get() = agentId != null && folder != null
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

    /** Saves the browsed folder as this agent's default cwd for new conversations. */
    fun makeDefault()

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
    /** letta-mobile-k5nbq.9: where "Make default for this agent" saves; null hides that control. */
    private val agentDefaults: AgentDefaultsRepository? = null,
) : WorkingDirectoryPickerActions {
    private val _state = MutableStateFlow(WorkingDirectoryPickerState())
    val state: StateFlow<WorkingDirectoryPickerState> = _state.asStateFlow()

    private var browseJob: Job? = null
    private var worktreesJob: Job? = null
    private var createJob: Job? = null

    /** Opens on the conversation's [currentCwd] (when known), with its worktrees. */
    fun open(currentCwd: String?, agentId: AgentId? = null) {
        val cwd = devicePathOrNull(currentCwd)?.takeIf { it.isAbsolute }?.normalized
        _state.value = WorkingDirectoryPickerState(
            isOpen = true,
            currentCwd = cwd?.value,
            recents = recents?.recent?.value.orEmpty(),
            createFolderUnavailableReason = if (folderCreator == null) WorkingDirectoryPickerGates.CREATE_FOLDER_UNAVAILABLE else null,
            worktree = WorktreePaths.locate(cwd),
            agentId = agentId?.takeIf { agentDefaults != null && it.value.isNotBlank() },
        )
        if (cwd != null) {
            browse(cwd.value)
            loadWorktrees(WorktreePaths.repositoryOf(cwd))
        }
    }

    override fun browse(path: String) {
        val target = absoluteOrReport(path)?.value ?: return
        browseJob?.cancel()
        _state.update { it.copy(folder = target, folders = emptyList(), hasMore = false, isLoading = true, error = null) }
        browseJob = scope.launch {
            val result = attempt { source.list(target) }
            _state.update { current ->
                if (!DevicePath(target).sameAs(devicePathOrNull(current.folder))) return@update current
                result.fold(
                    onSuccess = { listing -> current.copy(folders = listing.folders, hasMore = listing.hasMore, isLoading = false) },
                    onFailure = { error -> current.copy(isLoading = false, error = messageOf(error)) },
                )
            }
            if (result.exceptionOrNull()?.message == DirectoryBrowseErrors.NOT_FOUND) forgetRecent(DevicePath(target))
        }
    }

    override fun up() {
        devicePathOrNull(_state.value.folder)?.parent?.let { browse(it.value) }
    }

    override fun createFolder(name: String) {
        val creator = folderCreator ?: return
        val parent = _state.value.folder ?: return
        if (!FolderName(name).isValid) {
            _state.update { it.copy(error = DirectoryBrowseErrors.INVALID_NAME) }
            return
        }
        _state.update { it.copy(isCreatingFolder = true, error = null) }
        createJob?.cancel()
        // Cancelled by dismiss(): a picker closed mid-create neither reopens nor shows a stale result.
        createJob = scope.launch {
            val created = attempt { creator.create(parent, name.trim()) }
            _state.update { it.copy(isCreatingFolder = false, error = created.exceptionOrNull()?.let(::messageOf)) }
            created.getOrNull()?.let(::browse)
        }
    }

    override fun choose(path: String) {
        val chosen = absoluteOrReport(path) ?: return
        recents?.record(chosen)
        dismiss()
        onChosen(chosen.value)
    }

    override fun makeDefault() {
        val agentId = _state.value.agentId ?: return
        val chosen = absoluteOrReport(_state.value.folder ?: return) ?: return
        recents?.record(chosen)
        agentDefaults?.setCwd(agentId, chosen.value)
    }

    override fun dismiss() {
        browseJob?.cancel()
        worktreesJob?.cancel()
        createJob?.cancel()
        _state.value = WorkingDirectoryPickerState()
    }

    override fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private fun loadWorktrees(repository: DevicePath) {
        worktreesJob?.cancel()
        _state.update { it.copy(worktrees = WorktreeListState(repository = repository.value, isLoading = true)) }
        worktreesJob = scope.launch {
            // No `.letta/worktrees` (or no browsing on this connection) is simply no worktrees.
            val paths = attempt { source.list(WorktreePaths.worktreesFolder(repository).value) }
                .map { listing -> listing.folders.map { DevicePath(listing.path).child(FolderName(it)).value } }
                .getOrDefault(emptyList())
            _state.update { it.copy(worktrees = WorktreeListState(repository = repository.value, paths = paths)) }
        }
    }

    private fun forgetRecent(path: DevicePath) {
        val store = recents ?: return
        if (store.recent.value.none { path.sameAs(DevicePath(it)) }) return
        store.forget(path)
        _state.update { it.copy(recents = store.recent.value) }
    }

    /** [path] normalized when absolute; otherwise shows why and returns null. */
    private fun absoluteOrReport(path: String): DevicePath? {
        val candidate = DevicePath(path)
        if (candidate.isAbsolute) return candidate.normalized
        _state.update { it.copy(error = WorkingDirectoryPickerGates.NOT_ABSOLUTE) }
        return null
    }

    private fun messageOf(error: Throwable): String = when (error) {
        is DirectoryBrowseException -> error.message ?: DirectoryBrowseErrors.NOT_FOUND
        else -> error.message ?: "Something went wrong."
    }
}

/** [block]'s result or failure; cancellation still cancels. */
private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
    runCatching { block() }.onFailure { if (it is CancellationException) throw it }
