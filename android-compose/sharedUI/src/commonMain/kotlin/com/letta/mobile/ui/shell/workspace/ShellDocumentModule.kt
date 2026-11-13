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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfigStore
import com.letta.mobile.data.shell.layout.edit.ShellLayoutCatalog
import com.letta.mobile.data.shell.layout.edit.ShellLayoutEditResult
import com.letta.mobile.data.shell.layout.edit.ShellLayoutEditRule
import com.letta.mobile.data.shell.layout.edit.SharedNotesPlacement
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.shared_notes_back_to_list
import com.letta.mobile.sharedui.resources.shared_notes_capped
import com.letta.mobile.sharedui.resources.shared_notes_close_view
import com.letta.mobile.sharedui.resources.shared_notes_denied
import com.letta.mobile.sharedui.resources.shared_notes_empty
import com.letta.mobile.sharedui.resources.shared_notes_loading
import com.letta.mobile.sharedui.resources.shared_notes_more_failed
import com.letta.mobile.sharedui.resources.shared_notes_none_yet
import com.letta.mobile.sharedui.resources.shared_notes_not_shown
import com.letta.mobile.sharedui.resources.shared_notes_open_denied
import com.letta.mobile.sharedui.resources.shared_notes_open_unavailable
import com.letta.mobile.sharedui.resources.shared_notes_retry
import com.letta.mobile.sharedui.resources.shared_notes_show_more
import com.letta.mobile.sharedui.resources.shared_notes_side_lane_full
import com.letta.mobile.sharedui.resources.shared_notes_title
import com.letta.mobile.sharedui.resources.shared_notes_unavailable
import com.letta.mobile.sharedui.resources.shared_notes_untitled
import com.letta.mobile.sharedui.resources.shared_notes_withdrawn
import com.letta.mobile.ui.components.LettaRefreshAction
import com.letta.mobile.ui.components.LettaRefreshable
import com.letta.mobile.ui.components.isPointerHost
import com.letta.mobile.ui.components.row.LettaListRow
import com.letta.mobile.ui.components.row.LettaListRowSpec
import com.letta.mobile.ui.document.workspace.DocumentWorkspace
import com.letta.mobile.ui.host.LocalOverlayHost
import com.letta.mobile.ui.host.Overlay
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.shell.sidebar.LocalSharedNotesToggle
import com.letta.mobile.ui.shell.sidebar.SharedNotesToggle
import com.letta.mobile.ui.shell.modules.UnavailableModule
import com.letta.mobile.ui.theme.LettaBusyBar
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.CoroutineScope
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

/** The notes the host shares with this device, bound by the platform shell; null where it cannot reach any. */
val LocalSharedNotebooks = staticCompositionLocalOf<SharedNotebooks?> { null }

/**
 * The one browser the workspace provides: an already provided one, else one over [LocalSharedNotebooks],
 * kept for as long as that binding stands.
 */
@Composable
internal fun rememberSharedNoteBrowser(): SharedNoteBrowser? {
    val provided = LocalShellSharedNotes.current
    val notebooks = LocalSharedNotebooks.current
    return remember(provided, notebooks) { provided ?: notebooks?.let(::SharedNoteBrowser) }
}

/** Lets every shared-notes view inside [content] list and open notes through [notebooks]. */
@Composable
fun ProvideShellSharedNotes(notebooks: SharedNotebooks?, content: @Composable () -> Unit) {
    val browser = remember(notebooks) { notebooks?.let(::SharedNoteBrowser) }
    CompositionLocalProvider(LocalShellSharedNotes provides browser, content = content)
}

/**
 * The person's shared-notes toggle over this workspace's own layout [store] (never the process-wide
 * attached one, which is another window's while two shells are open): null while the host cannot
 * reach shared notes or the workspace has no store. A person's own toggle is kept in the layout
 * history (so it can be stepped back) but raises no "layout changed" notice: the person made it. A
 * toggle the layout rules refuse tells the person why, in the window's notice line.
 */
@Composable
internal fun rememberSharedNotesToggle(browser: SharedNoteBrowser?, store: ShellLayoutConfigStore?): SharedNotesToggle? {
    val layout = store?.config?.collectAsState()?.value
    val tell = rememberSharedNotesRefusalNotice()
    return remember(browser, store, layout, tell) {
        val shown = layout?.let(SharedNotesPlacement::shown) == true
        if (browser == null || store == null) null else SharedNotesToggle(shown) { toggleSharedNotes(store)?.let(tell) }
    }
}

/** Why the person's shared-notes toggle left the layout as it was. */
internal enum class SharedNotesRefusal {
    /** The side lane already holds as many views as it may; the person must close one first. */
    SIDE_LANE_FULL,

    /** Any other refusal, or the layout changed under the toggle. */
    NOT_APPLIED,
}

/** Shows or hides the shared-notes view in [store]'s layout; why not when the edit is refused or stale, else null. */
internal fun toggleSharedNotes(store: ShellLayoutConfigStore): SharedNotesRefusal? {
    val before = store.config.value
    return when (val result = SharedNotesPlacement.toggled(before)) {
        is ShellLayoutEditResult.Applied -> SharedNotesRefusal.NOT_APPLIED.takeIf { store.commit(result.after, expected = before) == null }
        is ShellLayoutEditResult.Rejected -> refusal(result)
    }
}

private fun refusal(rejected: ShellLayoutEditResult.Rejected): SharedNotesRefusal {
    val full = rejected.errors.any { it.rule == ShellLayoutEditRule.LANE_FULL }
    return if (full) SharedNotesRefusal.SIDE_LANE_FULL else SharedNotesRefusal.NOT_APPLIED
}

/** Tells the person, in the window's notice line, why the toggle did nothing and how to make room. */
@Composable
private fun rememberSharedNotesRefusalNotice(): (SharedNotesRefusal) -> Unit {
    val overlays = LocalOverlayHost.current
    val full = stringResource(Res.string.shared_notes_side_lane_full, ShellLayoutCatalog.lane(ShellLanes.SIDE)?.maxSlots ?: 0)
    val notApplied = stringResource(Res.string.shared_notes_not_shown)
    return remember(overlays, full, notApplied) {
        { refusal ->
            val message = if (refusal == SharedNotesRefusal.SIDE_LANE_FULL) full else notApplied
            overlays?.show(Overlay.Snackbar(message, key = SHARED_NOTES_REFUSAL_KEY))
        }
    }
}

private const val SHARED_NOTES_REFUSAL_KEY = "shared-notes-refused"

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
            ListHeader(refreshing = state.loading, onRefresh = { scope.launch { browser.refresh() } })
            SharedNoteList(state, browser, scope)
        } else {
            OpenSharedNote(workspace, browser)
        }
    }
}

/** The list's title, its one refresh (pointer hosts only: touch pulls the list) and the view's close. */
@Composable
private fun ListHeader(refreshing: Boolean, onRefresh: () -> Unit) {
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
        LettaRefreshAction(refreshing, onRefresh)
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

/**
 * The list body, reloaded through [LettaRefreshable]: a pull on touch (where the pull's indicator shows
 * the reload), the header's refresh on a pointer host (where the busy bar does). Every state scrolls,
 * so a pull reaches the refresh from the list, an empty or refused listing and an unreachable host alike.
 */
@Composable
private fun ColumnScope.SharedNoteList(state: SharedNoteBrowserState, browser: SharedNoteBrowser, scope: CoroutineScope) {
    val refresh: () -> Unit = { scope.launch { browser.refresh() } }
    if (state.loading && isPointerHost()) LettaBusyBar(Modifier.fillMaxWidth())
    state.openProblem?.let { problem -> Notice(openProblemText(problem), ShellDocumentTags.OPEN_PROBLEM, error = true) }
    LettaRefreshable(refreshing = state.loading, onRefresh = refresh, modifier = Modifier.fillMaxWidth().weight(1f)) {
        ListingBody(state, browser, scope, refresh)
    }
}

/** What the host's latest listing shows: the notes, or why there are none; "Try again" asks again. */
@Composable
private fun ListingBody(state: SharedNoteBrowserState, browser: SharedNoteBrowser, scope: CoroutineScope, retry: () -> Unit) {
    when (val listing = state.listing) {
        null -> ScrollingNotice { Notice(Res.string.shared_notes_loading, ShellDocumentTags.LOADING) }
        SharedNoteListing.Denied -> ScrollingNotice { Notice(Res.string.shared_notes_denied, ShellDocumentTags.DENIED) }
        is SharedNoteListing.Unavailable -> ScrollingNotice(ShellDocumentTags.UNAVAILABLE) {
            Notice(Res.string.shared_notes_unavailable, tag = null)
            TextButton(onClick = retry, modifier = Modifier.padding(start = LettaDimens.Space.sm)) {
                Text(stringResource(Res.string.shared_notes_retry))
            }
        }
        is SharedNoteListing.Listed -> ListedNotes(listing, state, browser, scope)
    }
}

/** A state with no rows: a column that scrolls (with nothing to scroll) so a pull on it still reaches the refresh. */
@Composable
private fun ScrollingNotice(tag: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val tagged = tag?.let { Modifier.testTag(it) } ?: Modifier
    Column(tagged.fillMaxSize().verticalScroll(rememberScrollState()), content = content)
}

/**
 * The listed notes and, while the host has more to check, a "Show more" control at the end that
 * appends the next page. A page that failed to load keeps what is shown and says so beside the control.
 * Once the browser holds as many notes as it will, the end of the list says only the first ones show.
 */
@Composable
private fun ListedNotes(
    listing: SharedNoteListing.Listed,
    state: SharedNoteBrowserState,
    browser: SharedNoteBrowser,
    scope: CoroutineScope,
) {
    val more = listing.next?.let { MoreNotes(loading = state.loadingMore, failed = state.moreFailed) }
    if (listing.notes.isEmpty() && more == null) {
        ScrollingNotice { Notice(Res.string.shared_notes_empty, ShellDocumentTags.EMPTY) }
        return
    }
    val actions = NoteActions(
        open = { note -> scope.launch { browser.open(note.id) } },
        more = { scope.launch { browser.loadMore() } },
    )
    NoteRows(listing.notes, more, enabled = state.opening == null, capped = state.capped, actions)
}

/** Whether the next page is being asked for, and whether the latest ask failed. */
private data class MoreNotes(val loading: Boolean, val failed: Boolean)

/** What the list's rows and its "Show more" control do. */
private class NoteActions(val open: (SharedNote) -> Unit, val more: () -> Unit)

@Composable
private fun NoteRows(notes: List<SharedNote>, more: MoreNotes?, enabled: Boolean, capped: Boolean, actions: NoteActions) {
    val untitled = stringResource(Res.string.shared_notes_untitled)
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = LettaDimens.Space.sm).testTag(ShellDocumentTags.LIST),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
    ) {
        // Only while the host has more to check: an empty listing with nothing more is the empty state.
        if (notes.isEmpty()) item(key = NONE_YET_KEY) { Notice(Res.string.shared_notes_none_yet, tag = null) }
        items(notes, key = { it.id.value }) { note ->
            LettaListRow(
                spec = LettaListRowSpec(title = note.title.ifBlank { untitled }, icon = LettaIcons.FileOpen, enabled = enabled),
                onClick = { actions.open(note) },
                modifier = Modifier.testTag(ShellDocumentTags.row(note)),
            )
        }
        more?.let { item(key = MORE_KEY) { ShowMore(it, actions.more) } }
        if (capped) item(key = CAPPED_KEY) { CappedNotice() }
    }
}

/** The end of a list cut at the most notes the browser holds; a refresh lists again from the start. */
@Composable
private fun CappedNotice() {
    NoticeText(stringResource(Res.string.shared_notes_capped, SharedNoteBrowser.MAX_HELD), CAPPED_TAG)
}

/** The end of a list the host has more of: a full-width "Show more", busy while it asks, with why the last ask failed. */
@Composable
private fun ShowMore(more: MoreNotes, onMore: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (more.failed) Notice(Res.string.shared_notes_more_failed, ShellDocumentTags.MORE_FAILED, error = true)
        if (more.loading) LettaBusyBar(Modifier.fillMaxWidth())
        TextButton(onClick = onMore, enabled = !more.loading, modifier = Modifier.fillMaxWidth().testTag(ShellDocumentTags.MORE)) {
            Text(stringResource(Res.string.shared_notes_show_more), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private const val MORE_KEY = "shell-document-more"
private const val NONE_YET_KEY = "shell-document-none-yet"
private const val CAPPED_KEY = "shell-document-capped"

/** The notice ending a list cut at [SharedNoteBrowser.MAX_HELD] notes. */
private const val CAPPED_TAG = "shell-document-capped"

@Composable
private fun Notice(text: StringResource, tag: String?, error: Boolean = false) {
    NoticeText(stringResource(text), tag, error)
}

@Composable
private fun NoticeText(text: String, tag: String?, error: Boolean = false) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val tagged = tag?.let { Modifier.testTag(it) } ?: Modifier
    Text(
        text,
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
