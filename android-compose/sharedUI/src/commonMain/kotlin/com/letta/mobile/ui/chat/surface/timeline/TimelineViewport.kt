package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.letta.mobile.data.chat.projection.ChatRenderItem
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

/**
 * How far above the viewport the owning prompt is looked for. Bounded because it runs per scroll
 * frame; past it the transcript scrolls with no pinned prompt (desktop PinnedPromptScanLimit).
 */
private const val PINNED_PROMPT_SCAN_LIMIT = 400

/**
 * The user prompt that owns what is on screen, to pin as a header (desktop rememberPinnedPrompt).
 *
 * In a reversed list the visual top is the HIGHEST visible index, and a prompt's answer (newer)
 * sits at a LOWER index beneath it, so the owner is the first prompt at or above the topmost
 * visible row. Compose's stickyHeader cannot express this: in a reversed list it pins to the
 * bottom. Null while the prompt's own row is on screen, so it is never drawn twice, and null while
 * ANY prompt is on screen: the pinned copy would sit over it (two "You" bubbles overlapping), and
 * a prompt in view already says what the rows below it answer.
 */
@Composable
internal fun rememberPinnedPrompt(
    listState: LazyListState,
    itemCount: Int,
    itemAt: (Int) -> ChatRenderItem?,
): State<ChatRenderItem?> {
    val currentCount = rememberUpdatedState(itemCount)
    val currentItemAt = rememberUpdatedState(itemAt)
    return remember(listState) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            val top = visible.maxOfOrNull { it.index } ?: return@derivedStateOf null
            if (visible.any { row -> currentItemAt.value(row.index)?.isUserPrompt() == true }) return@derivedStateOf null
            val end = minOf(currentCount.value, top + PINNED_PROMPT_SCAN_LIMIT)
            (top until end).firstNotNullOfOrNull { index -> currentItemAt.value(index)?.takeIf(ChatRenderItem::isUserPrompt) }
        }
    }
}
