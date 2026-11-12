package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceController
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceProblem
import com.letta.mobile.data.canvas.workspace.SharedNote
import com.letta.mobile.data.canvas.workspace.SharedNoteBrowser
import com.letta.mobile.data.canvas.workspace.SharedNoteBrowserState
import com.letta.mobile.data.canvas.workspace.SharedNoteListing
import com.letta.mobile.data.canvas.workspace.SharedNotebooks
import com.letta.mobile.data.shell.layout.ShellLayoutConfigStore
import com.letta.mobile.data.shell.layout.edit.ShellLayoutEditResult
import com.letta.mobile.data.shell.layout.edit.SharedNotesPlacement
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.shared_notes_back_to_list
import com.letta.mobile.sharedui.resources.shared_notes_close_view
import com.letta.mobile.sharedui.resources.shared_notes_denied
import com.letta.mobile.sharedui.resources.shared_notes_empty
import com.letta.mobile.sharedui.resources.shared_notes_loading
import com.letta.mobile.sharedui.resources.shared_notes_open_denied
import com.letta.mobile.sharedui.resources.shared_notes_open_unavailable
import com.letta.mobile.sharedui.resources.shared_notes_refresh
import com.letta.mobile.sharedui.resources.shared_notes_retry
import com.letta.mobile.sharedui.resources.shared_notes_title
import com.letta.mobile.sharedui.resources.shared_notes_unavailable
import com.letta.mobile.sharedui.resources.shared_notes_untitled
import com.letta.mobile.sharedui.resources.shared_notes_withdrawn
import com.letta.mobile.ui.components.row.LettaListRow
import com.letta.mobile.ui.components.row.LettaListRowSpec
import com.letta.mobile.ui.document.workspace.DocumentWorkspace
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.shell.modules.LocalSharedNotesToggle
import com.letta.mobile.ui.shell.modules.SharedNotesToggle
import com.letta.mobile.ui.shell.modules.UnavailableModule
import com.letta.mobile.ui.theme.LettaBusyBar
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The shared notes the host offers this device, as one browser per host binding. It lives above
 * the shell, so hiding the view, resizing between phone and wide layouts or remounting the lanes
 * keeps the open note and its draft. Null (the default) is a host that cannot reach shared notes.
 */
val LocalShellSharedNotes = staticCompositionLocalOf<SharedNoteBrowser?> { null }

/** Lets every shared-notes view inside [content] list and open notes through [notebooks]. */
@Composable
fun ProvideShellSharedNotes(notebooks: SharedNotebooks?, content: @Composable () -> Unit) {
    val browser = remember(notebooks) { notebooks?.let(::SharedNoteBrowser) }
    CompositionLocalProvider(LocalShellSharedNotes provides browser, content = content)
}

/**
 * The person's shared-notes toggle over the attached shell layout: null while the host cannot reach
 * shared notes or no layout is attached. A person's own toggle is kept in the layout history (so it
 * can be stepped back) but raises no "layout changed" notice: the person made it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
internal fun rememberSharedNotesToggle(): SharedNotesToggle? {
    val browser = LocalShellSharedNotes.current
    val port = LocalShellLayoutAgentPort.current
    val store by port.attached.collectAsState()
    val layouts = remember(port) { port.attached.flatMapLatest { it?.config ?: flowOf(null) } }
    val layout by layouts.collectAsState(port.attached.value?.config?.value)
    return remember(browser, store, layout) {
        val attached = store
        val shown = layout?.let(SharedNotesPlacement::shown)
        if (browser == null || attached == null) null else SharedNotesToggle(shown == true) { toggleSharedNotes(attached) }
    }
}

/** Shows or hides the shared-notes view in [store]'s layout; false when the edit is refused or stale. */
internal fun toggleSharedNotes(store: ShellLayoutConfigStore): Boolean {
    val before = store.config.value
    val applied = SharedNotesPlacement.toggled(before) as? ShellLayoutEditResult.Applied ?: return false
    return store.commit(applied.after, expected = before) != null
}

/**
 * The shared-notes view as a shell module: the host's list of notes shared with this device, and
 * the one the person opens, in the shared [DocumentWorkspace]. Every mount asks the host again;
 * opening rechecks the grant; a note the host stops sharing is marked and can be left.
 */
@Composable
internal fun ShellDocumentModule(modifier: Modifier) {
    val browser = LocalShellSharedNotes.current
    if (browser == null) {
        UnavailableModule(modifier)
        return
    }
    val state by browser.state.collectAsState()
    val scope = rememberCoroutineScope()
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).testTag(ShellDocumentTags.MODULE)) {
        val workspace = state.workspace
        if (workspace == null) {
            LaunchedEffect(browser) { browser.refresh() }
            ListHeader(onRefresh = { scope.launch { browser.refresh() } })
            SharedNoteList(state, browser, scope)
        } else {
            OpenSharedNote(workspace, browser)
        }
    }
}

@Composable
private fun ListHeader(onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = LettaDimens.Space.lg, end = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.shared_notes_title),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRefresh) {
            Icon(LettaIcons.Refresh, contentDescription = stringResource(Res.string.shared_notes_refresh))
        }
        CloseViewButton()
    }
}

/** Hides the view through the person's layout toggle; absent when no layout is attached. */
@Composable
private fun CloseViewButton() {
    val toggle = LocalSharedNotesToggle.current ?: return
    IconButton(onClick = toggle.toggle, modifier = Modifier.testTag(ShellDocumentTags.CLOSE)) {
        Icon(LettaIcons.Close, contentDescription = stringResource(Res.string.shared_notes_close_view))
    }
}

@Composable
private fun SharedNoteList(state: SharedNoteBrowserState, browser: SharedNoteBrowser, scope: CoroutineScope) {
    if (state.loading) LettaBusyBar(Modifier.fillMaxWidth())
    state.openProblem?.let { problem -> Notice(openProblemText(problem), ShellDocumentTags.OPEN_PROBLEM, error = true) }
    when (val listing = state.listing) {
        null -> Notice(Res.string.shared_notes_loading, ShellDocumentTags.LOADING)
        SharedNoteListing.Denied -> Notice(Res.string.shared_notes_denied, ShellDocumentTags.DENIED)
        is SharedNoteListing.Unavailable -> Column(Modifier.testTag(ShellDocumentTags.UNAVAILABLE)) {
            Notice(Res.string.shared_notes_unavailable, tag = null)
            TextButton(onClick = { scope.launch { browser.refresh() } }, modifier = Modifier.padding(start = LettaDimens.Space.sm)) {
                Text(stringResource(Res.string.shared_notes_retry))
            }
        }
        is SharedNoteListing.Listed -> if (listing.notes.isEmpty()) {
            Notice(Res.string.shared_notes_empty, ShellDocumentTags.EMPTY)
        } else {
            NoteRows(listing.notes, enabled = state.opening == null) { note -> scope.launch { browser.open(note.id) } }
        }
    }
}

@Composable
private fun NoteRows(notes: List<SharedNote>, enabled: Boolean, onOpen: (SharedNote) -> Unit) {
    val untitled = stringResource(Res.string.shared_notes_untitled)
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = LettaDimens.Space.sm).testTag(ShellDocumentTags.LIST),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
    ) {
        items(notes, key = { it.id.value }) { note ->
            LettaListRow(
                spec = LettaListRowSpec(title = note.title.ifBlank { untitled }, icon = LettaIcons.FileOpen, enabled = enabled),
                onClick = { onOpen(note) },
                modifier = Modifier.testTag(ShellDocumentTags.row(note)),
            )
        }
    }
}

@Composable
private fun Notice(text: StringResource, tag: String?, error: Boolean = false) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val tagged = tag?.let { Modifier.testTag(it) } ?: Modifier
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = tagged.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
    )
}

private fun openProblemText(problem: DocumentWorkspaceProblem): StringResource =
    if (problem == DocumentWorkspaceProblem.DENIED) Res.string.shared_notes_open_denied else Res.string.shared_notes_open_unavailable

/**
 * The open note in the shared workspace. Its own Close returns to the list once the note closed;
 * "All shared notes" does the same, and also leaves a note the host stopped sharing.
 */
@Composable
private fun ColumnScope.OpenSharedNote(workspace: DocumentWorkspaceController, browser: SharedNoteBrowser) {
    val scope = rememberCoroutineScope()
    val current by workspace.state.collectAsState()
    val note = current.current?.note
    val withdrawn by remember(note) { note?.state?.map { it.denied } ?: flowOf(false) }.collectAsState(false)
    LaunchedEffect(current.current) { if (current.current == null) browser.workspaceEmptied() }
    Row(
        Modifier.fillMaxWidth().padding(end = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { scope.launch { browser.closeNote() } }, modifier = Modifier.weight(1f).testTag(ShellDocumentTags.BACK)) {
            Icon(LettaIcons.ArrowBack, contentDescription = null)
            Text(
                stringResource(Res.string.shared_notes_back_to_list),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = LettaDimens.Space.sm).weight(1f),
            )
        }
        CloseViewButton()
    }
    if (withdrawn) Notice(Res.string.shared_notes_withdrawn, ShellDocumentTags.WITHDRAWN, error = true)
    DocumentWorkspace(workspace, Modifier.fillMaxWidth().weight(1f))
}
