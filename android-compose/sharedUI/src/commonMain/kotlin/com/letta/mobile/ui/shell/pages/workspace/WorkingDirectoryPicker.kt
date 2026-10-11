package com.letta.mobile.ui.shell.pages.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderPlus
import com.composables.icons.lucide.GitFork
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.letta.mobile.data.search.TextMatch
import com.letta.mobile.data.workspace.directory.DirectoryPaths
import com.letta.mobile.data.workspace.directory.WorkingDirectoryPickerActions
import com.letta.mobile.data.workspace.directory.WorkingDirectoryPickerState
import com.letta.mobile.data.workspace.directory.WorktreeLocation
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.cwd_picker_back_to_repository
import com.letta.mobile.sharedui.resources.cwd_picker_cancel
import com.letta.mobile.sharedui.resources.cwd_picker_close
import com.letta.mobile.sharedui.resources.cwd_picker_create
import com.letta.mobile.sharedui.resources.cwd_picker_empty
import com.letta.mobile.sharedui.resources.cwd_picker_folders
import com.letta.mobile.sharedui.resources.cwd_picker_go
import com.letta.mobile.sharedui.resources.cwd_picker_in_worktree
import com.letta.mobile.sharedui.resources.cwd_picker_more
import com.letta.mobile.sharedui.resources.cwd_picker_new_folder
import com.letta.mobile.sharedui.resources.cwd_picker_new_folder_name
import com.letta.mobile.sharedui.resources.cwd_picker_next_turn
import com.letta.mobile.sharedui.resources.cwd_picker_open_file_manager
import com.letta.mobile.sharedui.resources.cwd_picker_path
import com.letta.mobile.sharedui.resources.cwd_picker_recent
import com.letta.mobile.sharedui.resources.cwd_picker_system_dialog
import com.letta.mobile.sharedui.resources.cwd_picker_title
import com.letta.mobile.sharedui.resources.cwd_picker_up
import com.letta.mobile.sharedui.resources.cwd_picker_use
import com.letta.mobile.sharedui.resources.cwd_picker_worktree_search
import com.letta.mobile.sharedui.resources.cwd_picker_worktrees
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * What only some hosts can add to the picker: [openInFileManager] shows a folder in the OS file
 * manager, [pickWithSystemDialog] opens the OS folder dialog (both: the desktop with its bundled
 * local runtime, whose files are this computer's). Null leaves the control out.
 */
@Immutable
data class WorkingDirectoryPickerHost(
    val openInFileManager: ((String) -> Unit)? = null,
    val pickWithSystemDialog: (() -> Unit)? = null,
    /** Uses the sheet even in a wide window (a phone frame inside a desktop window). */
    val forceSheet: Boolean = false,
)

/** Test tags of the working-directory picker. */
object WorkingDirectoryPickerTags {
    const val ROOT = "cwd-picker"
    const val CLOSE = "cwd-picker-close"
    const val PATH = "cwd-picker-path"
    const val GO = "cwd-picker-go"
    const val UP = "cwd-picker-up"
    const val NEW_FOLDER = "cwd-picker-new-folder"
    const val NEW_FOLDER_NAME = "cwd-picker-new-folder-name"
    const val CREATE = "cwd-picker-create"
    const val CREATE_UNAVAILABLE = "cwd-picker-create-unavailable"
    const val FILE_MANAGER = "cwd-picker-file-manager"
    const val SYSTEM_DIALOG = "cwd-picker-system-dialog"
    const val ERROR = "cwd-picker-error"
    const val BANNER = "cwd-picker-worktree-banner"
    const val BACK_TO_REPOSITORY = "cwd-picker-back-to-repository"
    const val WORKTREE_SEARCH = "cwd-picker-worktree-search"
    const val USE = "cwd-picker-use"
    const val FOLDER_ROW = "cwd-picker-folder-"
    const val RECENT_ROW = "cwd-picker-recent-"
    const val WORKTREE_ROW = "cwd-picker-worktree-"
}

/**
 * letta-mobile-zxk2n.6 (G32): the shared working-directory picker: browse the device's folders,
 * type a path, create a folder, reopen a recent folder, or move between the repository and its
 * letta-code worktrees. "Use this folder" applies from the next turn. A dialog in a wide window,
 * a bottom sheet on a phone; it draws nothing while [state] is closed.
 */
@Composable
fun WorkingDirectoryPicker(
    state: WorkingDirectoryPickerState,
    actions: WorkingDirectoryPickerActions,
    modifier: Modifier = Modifier,
    host: WorkingDirectoryPickerHost = WorkingDirectoryPickerHost(),
) {
    if (!state.isOpen) return
    BoxWithConstraints(modifier) {
        if (maxWidth >= LettaDimens.Pane.wideBreakpoint && !host.forceSheet) {
            Dialog(onDismissRequest = actions::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .widthIn(max = LettaDimens.Pane.modalWidth)
                        .heightIn(max = LettaDimens.Pane.modalMaxHeight)
                        .padding(LettaDimens.Space.xl),
                ) { PickerContent(state, actions, host) }
            }
        } else {
            ModalBottomSheet(onDismissRequest = actions::dismiss) { PickerContent(state, actions, host) }
        }
    }
}

@Composable
private fun PickerContent(state: WorkingDirectoryPickerState, actions: WorkingDirectoryPickerActions, host: WorkingDirectoryPickerHost) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = LettaDimens.Space.lg).testTag(WorkingDirectoryPickerTags.ROOT),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        PickerHeader(onClose = actions::dismiss)
        state.worktree?.let { WorktreeBanner(it, onBackToRepository = { actions.choose(it.root) }) }
        PathRow(state, actions)
        PickerToolbar(state, actions, host)
        PickerError(state.error)
        if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.lg))
        PickerList(state, actions, Modifier.weight(1f, fill = false))
        PickerFooter(state, actions)
    }
}

@Composable
private fun PickerHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = LettaDimens.Space.lg, end = LettaDimens.Space.xs, top = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.cwd_picker_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onClose, modifier = Modifier.testTag(WorkingDirectoryPickerTags.CLOSE)) {
            Icon(Lucide.X, contentDescription = stringResource(Res.string.cwd_picker_close))
        }
    }
}

/** "In worktree x", with the way back to the repository the worktree belongs to. */
@Composable
private fun WorktreeBanner(worktree: WorktreeLocation, onBackToRepository: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.lg).testTag(WorkingDirectoryPickerTags.BANNER),
    ) {
        Row(
            modifier = Modifier.padding(start = LettaDimens.Space.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            Icon(Lucide.GitFork, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon))
            Text(
                stringResource(Res.string.cwd_picker_in_worktree, worktree.name),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBackToRepository, modifier = Modifier.testTag(WorkingDirectoryPickerTags.BACK_TO_REPOSITORY)) {
                Text(stringResource(Res.string.cwd_picker_back_to_repository))
            }
        }
    }
}

/** The folder being browsed, editable: typing a path and pressing Go browses there. */
@Composable
private fun PathRow(state: WorkingDirectoryPickerState, actions: WorkingDirectoryPickerActions) {
    var typed by remember(state.folder) { mutableStateOf(state.folder.orEmpty()) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        IconButton(onClick = actions::up, enabled = state.canGoUp, modifier = Modifier.testTag(WorkingDirectoryPickerTags.UP)) {
            Icon(Lucide.ArrowUp, contentDescription = stringResource(Res.string.cwd_picker_up))
        }
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text(stringResource(Res.string.cwd_picker_path)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { actions.browse(typed) }),
            modifier = Modifier.weight(1f).testTag(WorkingDirectoryPickerTags.PATH),
        )
        IconButton(onClick = { actions.browse(typed) }, modifier = Modifier.testTag(WorkingDirectoryPickerTags.GO)) {
            Icon(Lucide.ArrowRight, contentDescription = stringResource(Res.string.cwd_picker_go))
        }
    }
}

/** New folder (or why this host cannot make one), and the host's extras. */
@Composable
private fun PickerToolbar(state: WorkingDirectoryPickerState, actions: WorkingDirectoryPickerActions, host: WorkingDirectoryPickerHost) {
    var naming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.md)) {
        Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs), verticalAlignment = Alignment.CenterVertically) {
            ToolbarButton(NewFolderButton, enabled = state.canCreateFolder) { naming = true }
            val folder = state.folder
            host.openInFileManager?.takeIf { folder != null }?.let { open ->
                ToolbarButton(FileManagerButton) { folder?.let(open) }
            }
            host.pickWithSystemDialog?.let { pick -> ToolbarButton(SystemDialogButton, onClick = pick) }
        }
        state.createFolderUnavailableReason?.let { reason ->
            Text(
                reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = LettaDimens.Space.sm).testTag(WorkingDirectoryPickerTags.CREATE_UNAVAILABLE),
            )
        }
    }
    if (naming) {
        NewFolderDialog(
            onCreate = { name ->
                naming = false
                actions.createFolder(name)
            },
            onDismiss = { naming = false },
        )
    }
}

/** One toolbar control: its icon, label and test tag. */
@Immutable
private class ToolbarSpec(val icon: ImageVector, val label: StringResource, val tag: String)

private val NewFolderButton = ToolbarSpec(Lucide.FolderPlus, Res.string.cwd_picker_new_folder, WorkingDirectoryPickerTags.NEW_FOLDER)
private val FileManagerButton =
    ToolbarSpec(Lucide.ExternalLink, Res.string.cwd_picker_open_file_manager, WorkingDirectoryPickerTags.FILE_MANAGER)
private val SystemDialogButton = ToolbarSpec(Lucide.Folder, Res.string.cwd_picker_system_dialog, WorkingDirectoryPickerTags.SYSTEM_DIALOG)

@Composable
private fun ToolbarButton(spec: ToolbarSpec, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(spec.tag)) {
        Icon(spec.icon, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon))
        Text(stringResource(spec.label), modifier = Modifier.padding(start = LettaDimens.Space.xs))
    }
}

@Composable
private fun NewFolderDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val valid = DirectoryPaths.isValidFolderName(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.cwd_picker_new_folder)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.cwd_picker_new_folder_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onCreate(name) }),
                modifier = Modifier.testTag(WorkingDirectoryPickerTags.NEW_FOLDER_NAME),
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = valid, modifier = Modifier.testTag(WorkingDirectoryPickerTags.CREATE)) {
                Text(stringResource(Res.string.cwd_picker_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cwd_picker_cancel)) } },
    )
}

/** The current error, growing in place (snapping under reduced motion). */
@Composable
private fun PickerError(error: String?) {
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(error) }
    if (error != null) shown = error
    AnimatedVisibility(
        visible = error != null,
        enter = if (reduced) EnterTransition.None else expandVertically() + fadeIn(),
        exit = if (reduced) ExitTransition.None else shrinkVertically() + fadeOut(),
    ) {
        Text(
            shown.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = LettaDimens.Space.lg).testTag(WorkingDirectoryPickerTags.ERROR),
        )
    }
}

@Composable
private fun PickerList(state: WorkingDirectoryPickerState, actions: WorkingDirectoryPickerActions, modifier: Modifier) {
    var worktreeQuery by remember { mutableStateOf("") }
    val worktrees = remember(state.worktrees.paths, worktreeQuery) {
        state.worktrees.paths.filter { TextMatch.matches(worktreeQuery, DirectoryPaths.name(it)) }
    }
    val recentTitle = stringResource(Res.string.cwd_picker_recent)
    val worktreesTitle = stringResource(Res.string.cwd_picker_worktrees)
    val foldersTitle = stringResource(Res.string.cwd_picker_folders)
    LazyColumn(modifier.fillMaxWidth()) {
        if (state.recents.isNotEmpty()) {
            section(recentTitle)
            pathRows(state.recents, RecentRows, onClick = actions::browse)
        }
        if (state.worktrees.paths.isNotEmpty()) {
            section(worktreesTitle)
            item(key = "worktree-search") { WorktreeSearch(worktreeQuery, onQueryChange = { worktreeQuery = it }) }
            pathRows(worktrees, WorktreeRows, onClick = actions::browse)
        }
        val folder = state.folder ?: return@LazyColumn
        section(foldersTitle)
        pathRows(state.folders.map { DirectoryPaths.child(folder, it) }, FolderRows, onClick = actions::browse)
        folderListFooter(state)
    }
}

private fun LazyListScope.section(title: String) {
    item(key = "section-$title") {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = LettaDimens.Space.lg, top = LettaDimens.Space.md, bottom = LettaDimens.Space.xs),
        )
    }
}

/** How one list section draws its paths: the icon, the test-tag prefix, and whether the full path shows. */
@Immutable
private class PathRowStyle(val icon: ImageVector, val tagPrefix: String, val showPath: Boolean)

private val RecentRows = PathRowStyle(Lucide.History, WorkingDirectoryPickerTags.RECENT_ROW, showPath = true)
private val WorktreeRows = PathRowStyle(Lucide.GitFork, WorkingDirectoryPickerTags.WORKTREE_ROW, showPath = false)
private val FolderRows = PathRowStyle(Lucide.Folder, WorkingDirectoryPickerTags.FOLDER_ROW, showPath = false)

private fun LazyListScope.pathRows(paths: List<String>, style: PathRowStyle, onClick: (String) -> Unit) {
    items(paths, key = { style.tagPrefix + it }) { path ->
        ListItem(
            headlineContent = { Text(DirectoryPaths.name(path), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = if (style.showPath) {
                { Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) }
            } else {
                null
            },
            leadingContent = { Icon(style.icon, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon)) },
            modifier = Modifier.clickable { onClick(path) }.testTag(style.tagPrefix + DirectoryPaths.name(path)),
        )
    }
}

private fun LazyListScope.folderListFooter(state: WorkingDirectoryPickerState) {
    val note = when {
        state.isLoading || state.error != null -> null
        state.folders.isEmpty() -> Res.string.cwd_picker_empty
        state.hasMore -> Res.string.cwd_picker_more
        else -> null
    } ?: return
    item(key = "folder-note") {
        Text(
            stringResource(note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
        )
    }
}

@Composable
private fun WorktreeSearch(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(stringResource(Res.string.cwd_picker_worktree_search)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.lg).testTag(WorkingDirectoryPickerTags.WORKTREE_SEARCH),
    )
}

@Composable
private fun PickerFooter(state: WorkingDirectoryPickerState, actions: WorkingDirectoryPickerActions) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        Text(
            stringResource(Res.string.cwd_picker_next_turn),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        val folder = state.folder
        Button(
            onClick = { folder?.let(actions::choose) },
            enabled = folder != null && !DirectoryPaths.same(folder, state.currentCwd),
            modifier = Modifier.testTag(WorkingDirectoryPickerTags.USE),
        ) { Text(stringResource(Res.string.cwd_picker_use)) }
    }
}
