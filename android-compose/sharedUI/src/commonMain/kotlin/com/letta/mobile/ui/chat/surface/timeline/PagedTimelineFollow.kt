package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * letta-mobile-bglj6.1: the paged list's follow mode. Only the READER's scrolling detaches it: the
 * list's own scrolls (the send's glide to the new prompt, the stream's snap-back, the
 * scroll-to-latest glide) run through [ownScroll] and are never mistaken for the reader leaving
 * the newest edge. Before this, the send's own glide detached the follow it had just re-armed: the
 * scroll-to-latest button flashed up on every send, and when the glide ended while Paging was
 * mid-refresh (its newer edge not yet confirmed, as on a new chat's first send) the follow never
 * re-armed, so the reply streamed out of view behind a button the reader had to press.
 */
@Stable
internal class PagedFollow(following: Boolean) {
    var following: Boolean by mutableStateOf(following)

    private var ownScrolls by mutableIntStateOf(0)

    /** One of the list's own scrolls is running. */
    val ownScrollInProgress: Boolean get() = ownScrolls > 0

    /** Runs one of the list's own scrolls, so the follow does not read it as the reader's. */
    suspend fun ownScroll(scroll: suspend () -> Unit) {
        ownScrolls++
        try {
            scroll()
        } finally {
            ownScrolls--
        }
    }
}

/** The send's and the stream's hold on the newest row: what [rememberPagedFollow] watches. */
internal class PagedFollowInputs<I>(
    val listState: LazyListState,
    /** The reader opens away from the newest edge (a restored reading position): no follow yet. */
    val restoring: Boolean,
    /** Paging has confirmed the newest edge is the end of the list, not a page boundary. */
    val newerHistoryComplete: Boolean,
    val newestKey: String?,
    val newestIsUserPrompt: Boolean,
    /** Changes whenever the rows do: the stream's ticks. */
    val identity: I,
)

/**
 * The follow for one presentation of the paged list: leaving the newest edge detaches it, coming
 * back re-arms it, a send re-arms it and brings the reader to the new prompt, and while it holds
 * the stream keeps the list on the newest edge.
 */
@Composable
internal fun <K, I> rememberPagedFollow(key: K, inputs: PagedFollowInputs<I>): PagedFollow {
    val listState = inputs.listState
    val follow = remember(key) { PagedFollow(!inputs.restoring && listState.isAtNewestEdge()) }
    FollowTheNewestEdge(listState, follow, inputs.newerHistoryComplete)
    ForceFollowOnSend(listState, follow, inputs.newestKey, inputs.newestIsUserPrompt)
    SnapToNewestWhileFollowing(listState, follow, inputs.identity)
    return follow
}

/**
 * Leaving the newest edge stops following; coming back resumes, but only once that edge is the
 * true end of the list rather than a page boundary, or a mid-history page load would re-arm the
 * follow and yank the reader to the tail (desktop FollowTheNewestEdge). The list's own scrolls
 * ([PagedFollow.ownScroll]) are not the reader's and change nothing.
 */
@Composable
private fun FollowTheNewestEdge(listState: LazyListState, follow: PagedFollow, newerHistoryComplete: Boolean) {
    // Read as it is when a scroll ends: keying on it would restart the watch mid-scroll and lose it.
    val complete by rememberUpdatedState(newerHistoryComplete)
    LaunchedEffect(listState, follow) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress && !follow.ownScrollInProgress }.collect { readerScrolling ->
            if (readerScrolling) {
                follow.following = false
            } else if (wasScrolling && !listState.canScrollBackward && complete) {
                follow.following = true
            }
            wasScrolling = readerScrolling
        }
    }
}

/**
 * letta-mobile-bglj6.1.18: a new user prompt at the head is the user's own send, so the viewport
 * follows it — the port of LegacyTimelineList's force-follow (the legacy Android chat's
 * shouldForceScrollOnUserSend): re-arm the follow and bring the reader to the newest edge, wherever
 * they were scrolled; a glide, or a jump under reduced motion. The very first composition only
 * records the key, so a prompt already at the head when the list opens is not mistaken for a send.
 */
@Composable
private fun ForceFollowOnSend(
    listState: LazyListState,
    follow: PagedFollow,
    newestKey: String?,
    newestIsUserPrompt: Boolean,
) {
    val previousKey = remember { mutableStateOf<String?>(null) }
    val reducedMotion = LocalReducedMotion.current
    // The glide outlives the key that started it: the reply arriving at the head a moment later
    // changes the key again, and must not cancel the glide halfway to the edge.
    val scope = rememberCoroutineScope()
    LaunchedEffect(newestKey) {
        val isNewPrompt = newestIsUserPrompt && previousKey.value != null && newestKey != previousKey.value
        previousKey.value = newestKey
        if (isNewPrompt) {
            follow.following = true
            scope.launch {
                follow.ownScroll {
                    if (reducedMotion) listState.scrollToItem(0) else listState.animateScrollToItem(0)
                }
            }
        }
    }
}

/**
 * While following, the stream snaps the list back to the newest edge whenever it has left it (a
 * new row arriving at the head), coalesced to the legacy cadence ([STREAM_SNAP_INTERVAL_MS])
 * instead of once per raw live-overlay delta. A tick inside the interval waits out the rest of it
 * rather than being dropped, so the stream's last tick always lands.
 */
@Composable
private fun <I> SnapToNewestWhileFollowing(listState: LazyListState, follow: PagedFollow, identity: I) {
    val streamClock = remember { TimeSource.Monotonic.markNow() }
    val lastSnapAtMs = remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(identity, follow.following) {
        if (!follow.following) return@LaunchedEffect
        delay(streamSnapDelayMs(streamClock.elapsedNow().inWholeMilliseconds, lastSnapAtMs.value))
        // A scroll under way (the send's glide) is waited out, not skipped: if it ends short of the
        // edge, this tick still brings the list there. A reader's scroll detaches the follow, which
        // cancels this wait.
        snapshotFlow { listState.isScrollInProgress }.first { !it }
        if (listState.isAtNewestEdge()) return@LaunchedEffect
        lastSnapAtMs.value = streamClock.elapsedNow().inWholeMilliseconds
        follow.ownScroll { listState.scrollToItem(0) }
    }
}

/** How long a stream tick at [nowMs] waits before it may snap: none for the first, else the rest of the interval. */
internal fun streamSnapDelayMs(nowMs: Long, lastSnapAtMs: Long?): Long =
    if (lastSnapAtMs == null) 0L else (lastSnapAtMs + STREAM_SNAP_INTERVAL_MS - nowMs).coerceAtLeast(0L)

/** The streaming snap-back cadence; the legacy Android chat coalesced these to 96ms. */
internal const val STREAM_SNAP_INTERVAL_MS: Long = 96L
