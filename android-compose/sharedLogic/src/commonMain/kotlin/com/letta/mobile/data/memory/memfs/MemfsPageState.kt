package com.letta.mobile.data.memory.memfs

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.search.TextMatch

/** The MemFS browser's two views: the file tree with its editor, and the commit history. */
enum class MemfsTab { Files, History }

/** Where the file listing stands. [Refreshing] keeps the current files on screen. */
@Immutable
sealed interface MemfsLoad {
    data object Idle : MemfsLoad

    data object Loading : MemfsLoad

    data object Refreshing : MemfsLoad

    data object Loaded : MemfsLoad

    data class Failed(val message: String) : MemfsLoad
}

/**
 * The open memory file. [original] is the content last read from or saved to the server and
 * [draft] the user's edit of it. [conflict] is set when the server reports the file changed while
 * the draft had unsaved edits: the user then reloads it or keeps editing (a save overwrites).
 */
@Immutable
data class MemfsEditor(
    val path: String,
    val original: String = "",
    val draft: String = "",
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val conflict: Boolean = false,
    val isImage: Boolean = false,
) {
    val dirty: Boolean get() = !loading && !isImage && draft != original

    val canSave: Boolean get() = dirty && !saving
}

/** The commit list, for one file ([path]) or the whole repository (null), and one commit's diff. */
@Immutable
data class MemfsHistory(
    val path: String? = null,
    val commits: List<MemfsCommit> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val selectedSha: String? = null,
    val diff: List<MemfsFileDiff> = emptyList(),
    val diffLoading: Boolean = false,
    val diffError: String? = null,
) {
    val selectedCommit: MemfsCommit?
        get() = selectedSha?.let { sha -> commits.firstOrNull { it.sha == sha } }
}

/** A step the unsaved-changes guard is holding until the user discards or keeps their draft. */
@Immutable
sealed interface MemfsNavigation {
    data class Open(val path: String) : MemfsNavigation

    data object Close : MemfsNavigation

    data class SwitchAgent(val agentId: String) : MemfsNavigation
}

/** Everything the shared MemFS page draws (letta-mobile-bzvro.24). */
@Immutable
data class MemfsPageState(
    val agentId: String? = null,
    val load: MemfsLoad = MemfsLoad.Idle,
    val memfsEnabled: Boolean = true,
    val enabling: Boolean = false,
    val enableError: String? = null,
    val files: List<MemfsFile> = emptyList(),
    val query: String = "",
    val tab: MemfsTab = MemfsTab.Files,
    val editor: MemfsEditor? = null,
    val pendingNavigation: MemfsNavigation? = null,
    val history: MemfsHistory = MemfsHistory(),
) {
    private val matchingFiles: List<MemfsFile>
        get() = files.filter { TextMatch.matches(query, it.path, it.description) }.sortedBy { it.path }

    /** `system/` files, compiled into the agent's context. */
    val systemFiles: List<MemfsFile> get() = matchingFiles.filter { it.isSystem }

    /** Everything else: memory the agent reads on demand. */
    val externalFiles: List<MemfsFile> get() = matchingFiles.filterNot { it.isSystem }

    val isLoading: Boolean get() = load == MemfsLoad.Loading || load == MemfsLoad.Refreshing

    /** Why the file list is empty, or null when it has files to show (or is still loading). */
    val emptyReason: MemfsEmptyReason?
        get() = when {
            agentId == null -> MemfsEmptyReason.NoAgent
            load is MemfsLoad.Failed -> null
            load == MemfsLoad.Loading -> null
            !memfsEnabled -> MemfsEmptyReason.MemfsDisabled
            files.isEmpty() -> MemfsEmptyReason.NoFiles
            systemFiles.isEmpty() && externalFiles.isEmpty() -> MemfsEmptyReason.NoMatches
            else -> null
        }
}

enum class MemfsEmptyReason(val message: String) {
    NoAgent("Choose an agent to browse its memory files."),
    MemfsDisabled("Memory files are off for this agent."),
    NoFiles("This agent has no memory files yet."),
    NoMatches("No memory files match your filter."),
}

/** What the shared MemFS page can ask of its controller. */
interface MemfsPageActions {
    fun refresh()

    fun updateQuery(query: String)

    fun selectTab(tab: MemfsTab)

    fun openFile(path: String)

    fun closeFile()

    fun editDraft(text: String)

    fun save()

    /** Throws the draft away and shows the content last read from the server. */
    fun revertDraft()

    /** Discards the draft and carries out the step the unsaved-changes guard held. */
    fun confirmDiscard()

    fun cancelDiscard()

    /** Resolves a conflict by taking the server's version of the file. */
    fun reloadFromServer()

    /** Resolves a conflict by keeping the draft; the next save overwrites the server's change. */
    fun keepDraft()

    /** Opens the History view for [path], or the whole repository when null. */
    fun showHistory(path: String?)

    fun selectCommit(sha: String)

    fun clearCommit()

    fun enableMemfs()
}
