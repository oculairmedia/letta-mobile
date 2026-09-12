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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
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

    // Leaving the newest edge stops the follow; coming back to it resumes, but only once the
    // newest edge is genuinely the end of the list rather than a page boundary.
    LaunchedEffect(listState, settled.loadState.prepend.endOfPaginationReached) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                following = false
            } else if (wasScrolling && !listState.canScrollBackward &&
                settled.loadState.prepend.endOfPaginationReached
            ) {
                following = true
            }
            wasScrolling = scrolling
        }
    }

    LaunchedEffect(live, settled.itemSnapshotList, following) {
        if (following && !listState.isScrollInProgress) listState.scrollToItem(0)
    }

    // Restore where the reader left off, once — and only from rows already resident. Hunting for
    // the anchor through sequential history loads is the behaviour the windowed route exists to
    // avoid, so an anchor that never becomes resident simply yields to the tail.
    LaunchedEffect(presentation, settled.itemSnapshotList, live) {
        if (anchorRestored) return@LaunchedEffect
        val anchor = restoreAnchor ?: return@LaunchedEffect
        val index = canonicalRowIndex(live, settled.itemSnapshotList.items, anchor.first)
        if (index != null) {
            listState.scrollToItem(index, anchor.second)
            anchorRestored = true
        }
    }

    LaunchedEffect(presentation, anchorRestored) {
        if (!anchorRestored) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                val row = (index - live.size).takeIf { it in 0 until settled.itemCount }
                    ?.let { settled.peek(it) } ?: return@collect
                presentation.viewport = row.identity.value to offset
            }
    }

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
                items(settled.itemCount, key = settled.itemKey { "settled-" + it.item.key }) { index ->
                    val row = settled[index]
                    if (row == null) {
                        // A placeholder must still occupy space, or the list collapses toward the
                        // tail while a page loads and drags the reader with it.
                        Box(Modifier.height(48.dp))
                    } else {
                        Column {
                            row.deferred?.let {
                                DesktopDeferredWindow(presentation, row, Dispatchers.Default)
                            }
                            CanonicalRow(
                                row.item,
                                if (index + 1 < settled.itemCount) settled.peek(index + 1)?.item else null,
                                today,
                            )
                        }
                    }
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
