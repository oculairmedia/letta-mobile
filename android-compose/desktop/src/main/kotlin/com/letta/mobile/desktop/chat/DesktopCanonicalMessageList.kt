package com.letta.mobile.desktop.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.CombinedLoadStates
import androidx.paging.ItemSnapshotList
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.desktop.fadingEdges
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The paginated Desktop transcript. Paging owns load hints, retries and page dropping; this
 * composable never materializes the settled history, which is the whole point of the windowed
 * route — a 28k-message conversation must cost the same to open as a 30-message one.
 *
 * Laid out in reverse (index 0 is the newest row) so growth at the tail costs no re-measure of the
 * history above it, matching the Android paged list. Live rows sit ahead of the settled pages and
 * come from the overlay, not from the ledger.
 */
@Composable
internal fun DesktopCanonicalMessageList(
    presentation: CanonicalTimelinePresentation,
    modifier: Modifier = Modifier,
) {
    // A new presentation is a new conversation: its list state, follow mode and viewport anchor
    // must not be inherited from the one before it.
    key(presentation) {
        CanonicalMessageListContent(presentation, modifier)
    }
}

@Composable
private fun CanonicalMessageListContent(
    presentation: CanonicalTimelinePresentation,
    modifier: Modifier,
) {
    val settled = presentation.settled.collectAsLazyPagingItems()
    val live by presentation.live.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val anchoredTarget by presentation.target.collectAsState()

    // Only rows actually held by the list count as resident. This is what lets the engine
    // acknowledge settlement and drain the live overlay, so it is not optional bookkeeping.
    LaunchedEffect(presentation, settled) {
        snapshotFlow { settled.itemSnapshotList.items }.collect(presentation::onResidentRows)
    }

    val restoreAnchor = remember(presentation) { presentation.viewport }
    var following by remember(presentation) { mutableStateOf(restoreAnchor == null) }
    var anchorRestored by remember(presentation) { mutableStateOf(restoreAnchor == null) }

    FollowTheNewestEdge(listState, settled.loadState.prepend.endOfPaginationReached) { following = it }

    LaunchedEffect(live, settled.itemSnapshotList, following) {
        if (following && !listState.isScrollInProgress) listState.scrollToItem(0)
    }

    val rows = CanonicalRows(live, settled)
    if (!anchorRestored) {
        RestoreReadingPosition(listState, rows, restoreAnchor) { anchorRestored = true }
    } else {
        RecordReadingPosition(presentation, listState, rows)
    }

    val pinnedPrompt = rememberPinnedPrompt(listState, rows)

    val selectionColors = TextSelectionColors(
        handleColor = MaterialTheme.colorScheme.primary,
        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
    )
    val today = rememberCurrentDate()
    val topFade by animateFloatAsState(
        targetValue = if (listState.canScrollForward) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "canonicalTopFadeAlpha",
    )
    val bottomFade by animateFloatAsState(
        // Reverse layout inverts the scroll axis: "can scroll backward" is toward the newest row,
        // which is at the BOTTOM of the viewport.
        targetValue = if (listState.canScrollBackward) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "canonicalBottomFadeAlpha",
    )

    Box(modifier = modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp)
                    .fadingEdges(
                        topFadeAlpha = topFade,
                        bottomFadeAlpha = bottomFade,
                        topFadeLength = 72.dp,
                        bottomFadeLength = 44.dp,
                    ),
                contentPadding = PaddingValues(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(live.size, key = { "overlay-" + live[it].key }) { index ->
                    val older = live.getOrNull(index + 1)
                        ?: settled.itemSnapshotList.items.firstOrNull()?.item
                    CanonicalRow(live[index], older, today)
                }
                items(
                    settled.itemCount,
                    key = { index -> settled.peek(index)?.let { "settled-" + it.item.key } ?: "settled-slot-$index" },
                ) { index ->
                    CanonicalSettledRow(presentation, settled, index, today)
                }
                val load = settled.loadState
                if (load.refresh is LoadState.Loading || load.append is LoadState.Loading) {
                    item(key = "canonical-loading") { CircularProgressIndicator() }
                }
                if (load.refresh is LoadState.Error || load.append is LoadState.Error ||
                    load.prepend is LoadState.Error
                ) {
                    item(key = "canonical-retry") {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = canonicalLoadErrorMessage(load),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = settled::retry) { Text("Retry history") }
                        }
                    }
                }
            }
        }
        pinnedPrompt?.let { prompt ->
            // Aligned with the list's own horizontal padding so the pinned copy sits exactly where
            // the inline row sat. No backdrop fill: an opaque strip here would paint over the
            // ambient glow as a band, and the prompt card is itself opaque.
            Box(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 12.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                MessageListItem(item = prompt, streamingMessageId = null)
            }
        }
        presentation.missingTarget?.let { missing ->
            Text(
                text = "Message " + missing + " was not found. Showing recent history.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
            )
        }
        if (!following) {
            ScrollToLatestButton(
                onClick = {
                    following = true
                    scope.launch {
                        // Anchored on a search target, index 0 is the top of THAT window, not the
                        // tail. Re-anchor first so "latest" means the newest message either way.
                        if (anchoredTarget != null) presentation.navigate(null)
                        listState.scrollToItem(0)
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            )
        }
    }
}

/**
 * Follow mode: leaving the newest edge stops it, coming back resumes — but only once the newest
 * edge is genuinely the end of the list rather than a page boundary, or a mid-history page load
 * would silently re-arm the follow and yank the reader to the tail.
 */
@Composable
private fun FollowTheNewestEdge(
    listState: LazyListState,
    atNewestEdge: Boolean,
    onFollowChanged: (Boolean) -> Unit,
) {
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

/**
 * Puts the reader back where they left off, once, and only from rows already resident. Hunting for
 * the anchor through sequential history loads is exactly what the windowed route exists to avoid,
 * so an anchor that never becomes resident simply yields to the tail.
 */
@Composable
private fun RestoreReadingPosition(
    listState: LazyListState,
    rows: CanonicalRows,
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
    rows: CanonicalRows,
) {
    // The effect outlives any one CanonicalRows, and the index it resolves is an offset into the
    // live overlay. When the overlay drains, a captured rows would map the same index onto a
    // different row and save the wrong reading position, or stop saving one at all.
    val current by rememberUpdatedState(rows)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                val row = current.settledRowAt(index) ?: return@collect
                presentation.viewport = row.identity.value to offset
            }
    }
}

/**
 * The prompt that owns the region on screen. This list is laid out in reverse, so the visual top is
 * the HIGHEST visible index, and a prompt's answer — being newer — carries a LOWER index and renders
 * beneath it. The owning prompt is therefore the first user row at or above the topmost visible one.
 * Compose's own stickyHeader cannot express this: it pins to the start of the layout direction,
 * which in a reversed list is the BOTTOM of the pane.
 *
 * Null while the prompt's own row is visible, because a pinned copy would double-render it.
 */
@Composable
private fun rememberPinnedPrompt(listState: LazyListState, rows: CanonicalRows): ChatRenderItem? {
    val current = rememberUpdatedState(rows)
    val pinned by remember(listState) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            val top = visible.maxOfOrNull { it.index } ?: return@derivedStateOf null
            current.value.owningPrompt(top)?.let { (index, item) ->
                item.takeIf { visible.none { row -> row.index == index } }
            }
        }
    }
    return pinned
}

/**
 * How far above the viewport the owning prompt is looked for. A conversation can hold a very long
 * answer, but the search runs on every scroll frame, so it is bounded: past the limit the transcript
 * simply scrolls with no pinned prompt rather than walking the whole loaded window.
 */
private const val PinnedPromptScanLimit = 400

/**
 * The two row sources the list renders as one index space: the live overlay ahead of the settled
 * pages. Reads settled rows with `peek` throughout, because deciding what to pin or where the reader
 * is must not register a load and drag prefetch along with it.
 */
private class CanonicalRows(
    val live: List<ChatRenderItem>,
    val settled: LazyPagingItems<CanonicalTimelinePresentation.Row>,
) {
    val size: Int get() = live.size + settled.itemCount

    /**
     * Changes whenever either source does, so effects keyed on it re-run exactly when they should.
     * Both halves are named: a key is only as trustworthy as the equality behind it, and `Any`
     * hides which equality that is.
     */
    data class Identity(
        val live: List<ChatRenderItem>,
        val settled: ItemSnapshotList<CanonicalTimelinePresentation.Row>,
    )

    val identity: Identity get() = Identity(live, settled.itemSnapshotList)

    fun rowAt(index: Int): ChatRenderItem? =
        if (index < live.size) live.getOrNull(index) else settled.peek(index - live.size)?.item

    fun settledRowAt(index: Int): CanonicalTimelinePresentation.Row? =
        (index - live.size).takeIf { it in 0 until settled.itemCount }?.let(settled::peek)

    fun indexOf(identity: String): Int? =
        canonicalRowIndex(live, settled.itemSnapshotList.items, identity)

    /** The first user row at or above [top], with its index, or null within the search budget. */
    fun owningPrompt(top: Int): Pair<Int, ChatRenderItem>? {
        for (index in top until minOf(size, top + PinnedPromptScanLimit)) {
            val item = rowAt(index) ?: continue
            if (item.isUserPrompt()) return index to item
        }
        return null
    }
}

/**
 * One settled row, read through [LazyPagingItems.get] so the access registers with Paging and
 * drives prefetch. A not-yet-loaded row still occupies space, or the list collapses toward the
 * tail while a page loads and drags the reader with it.
 */
@Composable
private fun CanonicalSettledRow(
    presentation: CanonicalTimelinePresentation,
    settled: LazyPagingItems<CanonicalTimelinePresentation.Row>,
    index: Int,
    today: LocalDate,
) {
    val row = settled[index]
    if (row == null) {
        Box(Modifier.height(48.dp))
    } else {
        Column {
            row.deferred?.let { DesktopDeferredWindow(presentation, row, Dispatchers.Default) }
            CanonicalRow(
                row.item,
                if (index + 1 < settled.itemCount) settled.peek(index + 1)?.item else null,
                today,
            )
        }
    }
}

/**
 * One transcript row plus the day divider that begins its day. The divider is emitted with the
 * NEWER row and compared against the row below it, because only those two are needed — deciding it
 * from a materialized day-grouped list is exactly what the paged route cannot afford.
 */
@Composable
private fun CanonicalRow(item: ChatRenderItem, older: ChatRenderItem?, today: LocalDate) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        canonicalBoundaryDate(item, older)?.let { DesktopDayDividerRow(it, today) }
        // Streaming reveal belongs to the live overlay, which republishes the row on every token,
        // so no separate per-token highlight is threaded through here.
        MessageListItem(item = item, streamingMessageId = null)
    }
}

/** The first row of a day carries the divider; a row whose predecessor shares its day carries none. */
internal fun canonicalBoundaryDate(newer: ChatRenderItem, older: ChatRenderItem?): LocalDate? {
    val newerDay = newer.boundaryTimestamp.take(10)
    if (newerDay.isBlank()) return null
    if (older != null && newerDay == older.boundaryTimestamp.take(10)) return null
    return runCatching { LocalDate.parse(newerDay) }.getOrNull()
}

/** Resident rows only: an identity that has not been paged in yet has no index to scroll to. */
internal fun canonicalRowIndex(
    live: List<ChatRenderItem>,
    settled: List<CanonicalTimelinePresentation.Row>,
    identity: String,
): Int? {
    val inSettled = settled.indexOfFirst { it.identity.value == identity }
    if (inSettled >= 0) return live.size + inSettled
    return live.indexOfFirst { it.containsMessageId(identity) }.takeIf { it >= 0 }
}

private fun canonicalLoadErrorMessage(load: CombinedLoadStates): String =
    listOf(load.refresh, load.append, load.prepend)
        .filterIsInstance<LoadState.Error>()
        .firstOrNull()?.error?.message
        ?: "History could not be loaded"
