package com.letta.mobile.ui.shell.pages.memfs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.memory.memfs.MemfsPageActions
import com.letta.mobile.data.memory.memfs.MemfsPageState
import com.letta.mobile.data.memory.memfs.MemfsTab
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The MemFS browser shared by desktop and Android (letta-mobile-bzvro.24): an agent's memory
 * files split into system and external, an editor with an unsaved-changes guard and a prompt
 * when the agent rewrites the open file, and the memory repository's history with each
 * commit's diff.
 *
 * All state lives in [MemfsPageState] (sharedLogic's MemfsPageController); hosts own the chrome
 * and tune the rest through [options]. At or above [LettaDimens.Pane.wideBreakpoint] the list
 * docks beside the open file or commit; below it the page shows one at a time with a back action.
 */
@Composable
fun MemfsPage(
    state: MemfsPageState,
    actions: MemfsPageActions,
    modifier: Modifier = Modifier,
    options: MemfsPageOptions = MemfsPageOptions(),
) {
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag(MemfsPageTags.PAGE)) {
        val page = MemfsPageScope(state, actions, options, wide = maxWidth >= LettaDimens.Pane.wideBreakpoint)
        if (page.wide) WideMemfsLayout(page) else CompactMemfsLayout(page)
        state.pendingNavigation?.let { DiscardChangesDialog(page) }
    }
}

/**
 * Host presentation choices: [showTitle] false when the host already titles the screen; [touch]
 * grows the controls to touch-target size; [readOnly] hides editing (a host without write access).
 */
@Immutable
data class MemfsPageOptions(
    val showTitle: Boolean = true,
    val touch: Boolean = false,
    val readOnly: Boolean = false,
)

/** Test tags for the shared MemFS page. */
object MemfsPageTags {
    const val PAGE = "memfs_page"
    const val SEARCH = "memfs_search"
    const val REFRESH = "memfs_refresh"
    const val EMPTY = "memfs_empty"
    const val ERROR = "memfs_error"
    const val ENABLE = "memfs_enable"
    const val LIST = "memfs_list"
    const val EDITOR = "memfs_editor"
    const val EDITOR_FIELD = "memfs_editor_field"
    const val SAVE = "memfs_save"
    const val REVERT = "memfs_revert"
    const val BACK = "memfs_back"
    const val FILE_HISTORY = "memfs_file_history"
    const val CONFLICT = "memfs_conflict"
    const val CONFLICT_RELOAD = "memfs_conflict_reload"
    const val CONFLICT_KEEP = "memfs_conflict_keep"
    const val DISCARD_DIALOG = "memfs_discard_dialog"
    const val DISCARD_CONFIRM = "memfs_discard_confirm"
    const val DISCARD_CANCEL = "memfs_discard_cancel"
    const val HISTORY = "memfs_history"
    const val HISTORY_ALL = "memfs_history_all"
    const val DIFF = "memfs_diff"

    fun tab(tab: MemfsTab): String = "memfs_tab_${tab.name.lowercase()}"

    fun file(path: String): String = "memfs_file_$path"

    fun commit(sha: String): String = "memfs_commit_$sha"

    fun diffFile(path: String): String = "memfs_diff_file_$path"
}

internal class MemfsPageScope(
    val state: MemfsPageState,
    val actions: MemfsPageActions,
    val options: MemfsPageOptions,
    val wide: Boolean,
) {
    /** On a phone the open file or commit replaces the list. */
    val showsDetail: Boolean
        get() = when (state.tab) {
            MemfsTab.Files -> state.editor != null
            MemfsTab.History -> state.history.selectedSha != null
        }
}

@Composable
private fun WideMemfsLayout(page: MemfsPageScope) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(LettaDimens.Pane.sidePanelWidth).fillMaxHeight()) {
            MemfsHeader(page)
            MemfsListPane(page)
        }
        VerticalDivider()
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.weight(1f).fillMaxHeight()) {
            MemfsDetailPane(page)
        }
    }
}

@Composable
private fun CompactMemfsLayout(page: MemfsPageScope) {
    Column(Modifier.fillMaxSize()) {
        if (page.showsDetail) {
            MemfsDetailPane(page)
        } else {
            MemfsHeader(page)
            MemfsListPane(page)
        }
    }
}

/** The left pane: the file tree or the commit list, by tab. */
@Composable
private fun MemfsListPane(page: MemfsPageScope) {
    when (page.state.tab) {
        MemfsTab.Files -> MemfsFileList(page)
        MemfsTab.History -> MemfsCommitList(page)
    }
}

/** The right pane (or the whole page on a phone): the open file, or the selected commit's diff. */
@Composable
private fun MemfsDetailPane(page: MemfsPageScope) {
    when (page.state.tab) {
        MemfsTab.Files -> page.state.editor?.let { MemfsEditorPane(it, page) } ?: MemfsDetailHint(NO_FILE_OPEN)
        MemfsTab.History -> if (page.state.history.selectedSha != null) MemfsDiffPane(page) else MemfsDetailHint(NO_COMMIT_SELECTED)
    }
}

private const val NO_FILE_OPEN = "Open a memory file to read or edit it."
private const val NO_COMMIT_SELECTED = "Choose a commit to see what it changed."
