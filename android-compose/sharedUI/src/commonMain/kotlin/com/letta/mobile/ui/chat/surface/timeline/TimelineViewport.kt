package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.letta.mobile.data.chat.runtime.ChatViewportSnapshot
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * letta-mobile-bglj6.1: viewport arithmetic shared by the legacy and paged lists.
 *
 * Both lists are laid out in REVERSE (index 0 is the newest row, at the bottom), as Android's lists
 * are: growth at the tail then never re-measures the history above it, and loading older history
 * appends at the far end, so the reader's position needs no correction when a page lands.
 */

/**
 * [ChatViewportSnapshot] speaks chat order (the latest row has the highest index). A reversed list's
 * newest visible row is its LOWEST visible index, so it is mirrored before the shared
 * ChatViewportFollowPolicy reads it.
 */
internal fun LazyListState.reversedViewportSnapshot(isUserScrolling: Boolean): ChatViewportSnapshot {
    val info = layoutInfo
    val total = info.totalItemsCount
    val newestVisible = info.visibleItemsInfo.firstOrNull()?.index
    return ChatViewportSnapshot(
        totalItems = total,
        lastVisibleIndex = newestVisible?.let { total - 1 - it },
        isUserScrolling = isUserScrolling,
    )
}

/** How many rows from the oldest edge count as "near the top" for loading older history. */
internal const val LOAD_OLDER_THRESHOLD_ROWS: Int = 3

/** The oldest visible row is within [LOAD_OLDER_THRESHOLD_ROWS] of the end of what is resident. */
internal fun isNearOldestEdge(oldestVisibleIndex: Int?, totalItems: Int): Boolean =
    totalItems > 0 && oldestVisibleIndex != null && oldestVisibleIndex >= totalItems - LOAD_OLDER_THRESHOLD_ROWS

/** What decides whether the legacy list asks its owner for older or fewer messages. */
internal data class OlderHistoryGate(
    val hasMoreOlder: Boolean,
    val isLoadingOlder: Boolean,
    val residentMessages: Int,
    /** The owner pages history and can shrink its resident window (ChatSurfaceCapabilities.pagedHistory). */
    val canRelease: Boolean,
) {
    fun shouldLoadOlder(nearOldestEdge: Boolean): Boolean =
        nearOldestEdge && hasMoreOlder && !isLoadingOlder && residentMessages > 0

    /**
     * Sliding-window release (Android ChatMessageListReleaseOlderEffect): back near the newest
     * edge after the resident window has grown well past a viewport's worth, shrink it again.
     */
    fun shouldReleaseOlder(newestVisibleIndex: Int): Boolean =
        canRelease && !isLoadingOlder && residentMessages > RELEASE_OLDER_TRIGGER_MESSAGES &&
            newestVisibleIndex <= RELEASE_OLDER_SCROLL_THRESHOLD

    companion object {
        /** Below ChatTimelineProjector's 3000-message resident cap, so a release drops something. */
        const val RELEASE_OLDER_TRIGGER_MESSAGES: Int = 2500
        const val RELEASE_OLDER_SCROLL_THRESHOLD: Int = 20
    }
}

/** Asks for older history near the top and releases it again near the bottom. */
@Composable
internal fun OlderHistoryEffect(
    listState: LazyListState,
    gate: OlderHistoryGate,
    onLoadOlder: () -> Unit,
    onReleaseOlder: () -> Unit,
) {
    val currentGate by rememberUpdatedState(gate)
    val loadOlder by rememberUpdatedState(onLoadOlder)
    val releaseOlder by rememberUpdatedState(onReleaseOlder)
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            OlderEdgeProbe(
                nearOldest = isNearOldestEdge(info.visibleItemsInfo.lastOrNull()?.index, info.totalItemsCount),
                newestVisible = listState.firstVisibleItemIndex,
                gate = currentGate,
            )
        }.distinctUntilChanged().collect { probe ->
            if (probe.gate.shouldLoadOlder(probe.nearOldest)) loadOlder()
            if (probe.gate.shouldReleaseOlder(probe.newestVisible)) releaseOlder()
        }
    }
}

private data class OlderEdgeProbe(val nearOldest: Boolean, val newestVisible: Int, val gate: OlderHistoryGate)
