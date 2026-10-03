package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.CombinedLoadStates
import androidx.paging.ItemSnapshotList
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.TimelineRowAssembly
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.timeline_history_error
import com.letta.mobile.sharedui.resources.timeline_missing_target
import com.letta.mobile.sharedui.resources.timeline_retry_history
import com.letta.mobile.ui.mascot.MascotLoading
import com.letta.mobile.ui.theme.ChatTimelineDimens
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/** Everything the paged list reads. */
@Immutable
internal class PagedTimelineParams(
    val presentation: CanonicalTimelinePresentation,
    val agentId: String?,
    val thinking: Boolean,
    /** The conversation's messages, for the thinking row's "0:12  Running Bash" clock. */
    val thinkingMessages: List<UiMessage> = emptyList(),
    val bindings: TimelineRowBindings,
    val bottomReserve: Dp,
    /** The page's hoisted scroll position for this presentation. */
    val listState: LazyListState,
    /** Drawn instead of the list when history is confirmed empty (the welcome / starter prompts). */
    val emptyContent: @Composable () -> Unit,
    /** Host chrome floating over the list's top (see TimelineFrameOverlays.topReserve). */
    val topReserve: Dp = 0.dp,
)

private const val THINKING_KEY = "__thinking__"

/**
 * letta-mobile-bglj6.1: the canonical paged timeline. Paging owns load hints, retries and page
 * dropping; this never materializes the settled history, so a 28k-message conversation costs the
 * same to open as a 30-message one. Lifted from desktop's DesktopCanonicalMessageList and
 * Android's PagedChatMessageList (live/settled de-duplication through TimelineRowAssembly).
 *
 * A new presentation is a new conversation: list state, follow mode and anchor are not inherited.
 */
@Composable
internal fun PagedTimelineList(params: PagedTimelineParams, modifier: Modifier = Modifier) {
    key(params.presentation) { PagedTimelineContent(params, modifier) }
}

@Composable
private fun PagedTimelineContent(params: PagedTimelineParams, modifier: Modifier) {
    val presentation = params.presentation
    val settled = presentation.settled.collectAsLazyPagingItems()
    val live by presentation.live.collectAsState()
    val rows = rememberPagedRows(live, settled, if (params.thinking) 1 else 0)
    ObserveResidentRows(presentation, settled)

    when (pagedOpeningOf(settled.loadState, rows.size - rows.leading)) {
        PagedOpening.Loading -> TimelineLoading(params.agentId, modifier.fillMaxSize().padding(top = params.topReserve))
        PagedOpening.Empty -> Box(modifier.padding(top = params.topReserve)) { params.emptyContent() }
        PagedOpening.Ready -> PagedTimelineBody(params, settled, rows, modifier)
    }
}

@Composable
private fun PagedTimelineBody(
    params: PagedTimelineParams,
    settled: LazyPagingItems<CanonicalTimelinePresentation.Row>,
    rows: PagedRows,
    modifier: Modifier,
) {
    val presentation = params.presentation
    val listState = params.listState
    val scope = rememberCoroutineScope()
    val anchoredTarget by presentation.target.collectAsState()
    val restoreAnchor = remember(presentation) { presentation.viewport }
    var following by remember(presentation) { mutableStateOf(restoreAnchor == null && listState.isAtNewestEdge()) }
    var anchorRestored by remember(presentation) { mutableStateOf(restoreAnchor == null) }

    FollowTheNewestEdge(listState, settled.loadState.prepend.endOfPaginationReached) { following = it }
    LaunchedEffect(rows.identity, following) {
        if (following && !listState.isScrollInProgress) listState.scrollToItem(0)
    }
    if (!anchorRestored) {
        RestoreReadingPosition(listState, rows, restoreAnchor) { anchorRestored = true }
    } else {
        RecordReadingPosition(presentation, listState, rows)
    }
    val pinned = rememberPinnedPrompt(listState, rows.size, params.topReserve, rows::itemAt)
    val glide = rememberNewestEdgeGlide(listState)
    val today = rememberCurrentDate()
    // The newest row is the live overlay's head, not necessarily the owner state's last message.
    val newestId = remember(rows) { rows.itemAt(rows.leading)?.newestMessageId() }
    val bindings = remember(params.bindings, newestId) {
        TimelineRowBindings(params.bindings.contexts.withNewest(newestId), params.bindings.callbacks, params.bindings.pinch)
    }

    Box(modifier) {
        TimelineListFrame(
            listState = listState,
            bindings = bindings,
            overlays = TimelineFrameOverlays(
                pinnedPrompt = pinned,
                showScrollToLatest = !following,
                onScrollToLatest = {
                    following = true
                    scope.launch {
                        // Anchored on a search target, index 0 is the newest row of THAT window.
                        if (anchoredTarget != null) presentation.navigate(null)
                        glide.toNewest()
                        // The glide's own scroll stopped the follow; it ends on the newest edge.
                        following = true
                    }
                },
                glide = glide,
                bottomReserve = params.bottomReserve,
                topReserve = params.topReserve,
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            pagedRows(PagedRowsScope(rows, settled, params, today, bindings))
        }
        presentation.missingTarget?.let { missing ->
            Text(
                text = stringResource(Res.string.timeline_missing_target, missing),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = params.topReserve).padding(LettaDimens.Space.sm),
            )
        }
    }
}

/** Opening states the paged list decides from Paging's own load states. */
internal enum class PagedOpening { Loading, Empty, Ready }

internal fun pagedOpeningOf(load: CombinedLoadStates, residentRows: Int): PagedOpening = when {
    residentRows > 0 -> PagedOpening.Ready
    load.refresh is LoadState.Loading -> PagedOpening.Loading
    load.refresh is LoadState.Error -> PagedOpening.Ready
    load.refresh is LoadState.NotLoading &&
        load.prepend.endOfPaginationReached && load.append.endOfPaginationReached -> PagedOpening.Empty
    else -> PagedOpening.Loading
}

/** The two sources [PagedRows] is read from, compared by value to key effects on either. */
@Immutable
internal data class PagedRowsIdentity(
    val live: List<ChatRenderItem>,
    val settled: ItemSnapshotList<CanonicalTimelinePresentation.Row>,
)

/**
 * The thinking row, the live overlay and the settled pages as one index space, read from one
 * snapshot of each source (TimelineRowAssembly). Settled rows are read with `peek` here, because
 * deciding what to pin or where the reader is must not register a load and drag prefetch along.
 */
@Immutable
internal class PagedRows(
    val leading: Int,
    val assembly: TimelineRowAssembly,
    private val settled: LazyPagingItems<CanonicalTimelinePresentation.Row>,
    /** Changes whenever either source does, so effects keyed on it re-run exactly then. */
    val identity: PagedRowsIdentity,
) {
    val size: Int get() = leading + assembly.size
    val liveCount: Int get() = assembly.liveCount

    fun key(index: Int): String = assembly.key(index - leading) ?: "settled-slot-${index - leading}"

    fun itemAt(index: Int): ChatRenderItem? {
        val i = index - leading
        if (i < 0) return null
        return assembly.live.getOrNull(i) ?: settledRowAt(index)?.item
    }

    fun settledRowAt(index: Int): CanonicalTimelinePresentation.Row? =
        (index - leading - liveCount).takeIf { it in 0 until settled.itemCount }?.let(settled::peek)

    fun indexOf(identity: String): Int? =
        canonicalRowIndex(assembly.live, settled.itemSnapshotList.items, identity)?.let { it + leading }
}

@Composable
private fun rememberPagedRows(
    live: List<ChatRenderItem>,
    settled: LazyPagingItems<CanonicalTimelinePresentation.Row>,
    leading: Int,
): PagedRows {
    val snapshot = settled.itemSnapshotList
    return remember(live, snapshot, leading) {
        val assembly = TimelineRowAssembly.assemble(live, snapshot.map { it?.item?.key })
        PagedRows(leading, assembly, settled, identity = PagedRowsIdentity(live, snapshot))
    }
}

/** Resident rows only: an identity not paged in yet has no index (desktop canonicalRowIndex). */
internal fun canonicalRowIndex(
    live: List<ChatRenderItem>,
    settled: List<CanonicalTimelinePresentation.Row>,
    identity: String,
): Int? {
    val inSettled = settled.indexOfFirst { it.identity.value == identity }
    if (inSettled >= 0) return live.size + inSettled
    return live.indexOfFirst { it.containsMessageId(identity) }.takeIf { it >= 0 }
}

private class PagedRowsScope(
    val rows: PagedRows,
    val settled: LazyPagingItems<CanonicalTimelinePresentation.Row>,
    val params: PagedTimelineParams,
    val today: LocalDate,
    val bindings: TimelineRowBindings,
)

private fun LazyListScope.pagedRows(scope: PagedRowsScope) {
    val rows = scope.rows
    if (rows.leading > 0) item(key = THINKING_KEY) { ThinkingRow(scope.params.thinkingMessages) }
    items(count = rows.size - rows.leading, key = { rows.key(it + rows.leading) }) { offset ->
        val index = offset + rows.leading
        if (offset < rows.liveCount) {
            PagedRow(rows.assembly.live[offset], rows.itemAt(index + 1), scope)
        } else {
            SettledRow(scope, index)
        }
    }
    pagedLoadFooter(scope)
}

/**
 * One settled row through [LazyPagingItems.get], so the access registers with Paging and drives
 * prefetch. A not-yet-loaded row still occupies space, or the list would collapse toward the tail
 * while a page loads and drag the reader with it.
 */
@Composable
private fun SettledRow(scope: PagedRowsScope, index: Int) {
    val settledIndex = index - scope.rows.leading - scope.rows.liveCount
    val row = scope.settled[settledIndex]
    if (row == null) {
        Box(Modifier.height(ChatTimelineDimens.placeholderRowHeight))
    } else {
        PagedRow(row.item, scope.rows.itemAt(index + 1), scope)
    }
}

/** A row plus the divider that begins its day, decided against its older neighbour only. */
@Composable
private fun PagedRow(item: ChatRenderItem, older: ChatRenderItem?, scope: PagedRowsScope) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        pagedBoundaryDate(item, older)?.let { date ->
            DayDividerRow(date, scope.today)
        }
        TimelineItemRow(item, scope.bindings)
    }
}

private fun LazyListScope.pagedLoadFooter(scope: PagedRowsScope) {
    val load = scope.settled.loadState
    if (load.append is LoadState.Loading) {
        // The agent fetching its own history, not an anonymous spinner (wbin4.2).
        item(key = "canonical-loading") { MascotLoading(scope.params.agentId) }
    }
    val error = listOf(load.refresh, load.append, load.prepend).filterIsInstance<LoadState.Error>().firstOrNull()
    if (error != null) {
        item(key = "canonical-retry") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = error.error.message ?: stringResource(Res.string.timeline_history_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = scope.settled::retry) { Text(stringResource(Res.string.timeline_retry_history)) }
            }
        }
    }
}

/**
 * Only rows actually held by the list count as resident: this drains the live overlay. Every view
 * of a paged timeline (the full page, the docked panel, the minimised dock's bubble) must run it,
 * or the overlay never drains.
 *
 * Several views report the same pager and the last report wins, so a view that has not loaded yet
 * stays quiet: a freshly mounted [LazyPagingItems] reports an empty list while its first page is
 * still on its way, which would clear what the other views hold.
 */
@Composable
internal fun ObserveResidentRows(
    presentation: CanonicalTimelinePresentation,
    settled: LazyPagingItems<CanonicalTimelinePresentation.Row>,
) {
    LaunchedEffect(presentation, settled) {
        snapshotFlow { residentReport(settled.itemSnapshotList.items, settled.loadState.refresh) }
            .collect { rows -> if (rows != null) presentation.onResidentRows(rows) }
    }
}

/** What a view reports as resident: nothing (null) while its first page is still loading. */
internal fun <T> residentReport(rows: List<T>, refresh: LoadState): List<T>? =
    rows.takeUnless { it.isEmpty() && refresh is LoadState.Loading }

/**
 * Leaving the newest edge stops following; coming back resumes, but only once that edge is the
 * true end of the list rather than a page boundary, or a mid-history page load would re-arm the
 * follow and yank the reader to the tail (desktop FollowTheNewestEdge).
 */
@Composable
private fun FollowTheNewestEdge(listState: LazyListState, atNewestEdge: Boolean, onFollowChanged: (Boolean) -> Unit) {
    LaunchedEffect(listState, atNewestEdge) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                onFollowChanged(false)
            } else if (wasScrolling && !listState.canScrollBackward && atNewestEdge) {
                onFollowChanged(true)
            }
            wasScrolling = scrolling
        }
    }
}

/** Puts the reader back where they left off, once, from resident rows only. */
@Composable
private fun RestoreReadingPosition(
    listState: LazyListState,
    rows: PagedRows,
    anchor: Pair<String, Int>?,
    onRestored: () -> Unit,
) {
    LaunchedEffect(listState, rows.identity) {
        val target = anchor ?: return@LaunchedEffect onRestored()
        val index = rows.indexOf(target.first) ?: return@LaunchedEffect
        listState.scrollToItem(index, target.second)
        onRestored()
    }
}

/** Remembers where the reader is, so reopening this conversation lands on the same row. */
@Composable
private fun RecordReadingPosition(
    presentation: CanonicalTimelinePresentation,
    listState: LazyListState,
    rows: PagedRows,
) {
    // The index is an offset into the live overlay, which drains; read the CURRENT rows.
    val current = rememberUpdatedState(rows)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                val row = current.value.settledRowAt(index) ?: return@collect
                presentation.viewport = row.identity.value to offset
            }
    }
}
