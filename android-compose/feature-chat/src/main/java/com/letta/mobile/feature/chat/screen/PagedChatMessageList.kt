package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.RenderDiagnostics
import com.letta.mobile.ui.mascot.MascotLoading
import java.time.LocalDate

internal fun followNewestEdge(wasScrolling: Boolean, atNewestEdge: Boolean, prependExhausted: Boolean): Boolean =
    wasScrolling && atNewestEdge && prependExhausted

internal fun shouldRepositionAfterPagerRefresh(refresh: LoadState): Boolean =
    refresh is LoadState.Loading

internal fun displayedLiveRows(live: List<ChatRenderItem>, residentSettled: List<ChatRenderItem>): List<ChatRenderItem> {
    val settledKeys = residentSettled.mapTo(mutableSetOf()) { it.key }
    return live.filterNot { it.key in settledKeys }
}

internal enum class TimelineRowLifecycle { Mount, Dispose }

internal val LocalTimelineRowLifecycleObserver = staticCompositionLocalOf<(TimelineRowLifecycle, String) -> Unit> {
    { _, _ -> }
}

internal fun residentTargetIndex(
    rows: List<ChatRenderItem>,
    target: String,
    liveCount: Int,
    placeholdersBefore: Int,
): Int? = rows.indexOfFirst { it.containsMessageId(target) }
    .takeIf { it >= 0 }?.let { liveCount + placeholdersBefore + it }

/** Paging owns load hints, retries and dropping. Never materialize the settled snapshot. */
@Composable
internal fun PagedChatMessageList(
    presentation: ChatPagingPresentation,
    state: ChatUiState,
    callbacks: ChatContentCallbacks,
    appearance: ChatContentAppearance,
    modifier: Modifier = Modifier,
) {
    if (presentation.opening || presentation.openError != null) {
        androidx.compose.foundation.layout.Column(modifier) {
            // The agent opening its own conversation, not an anonymous wait (wbin4.2).
            if (presentation.openError == null) MascotLoading(state.agentId)
            Text(presentation.openError ?: "Opening conversation...")
            if (presentation.openError != null) TextButton(onClick = presentation.retryOpen) {
                Text("Retry")
            }
        }
        return
    }
    key(presentation) {
        PagedChatMessageListContent(presentation, state, callbacks, appearance, modifier)
    }
}

@Composable
private fun PagedChatMessageListContent(
    presentation: ChatPagingPresentation,
    state: ChatUiState,
    callbacks: ChatContentCallbacks,
    appearance: ChatContentAppearance,
    modifier: Modifier,
) {
    val routeTarget = if (presentation.hasBoundRoute) presentation.routeTarget else appearance.scrollToMessageId
    val pages = presentation.settled.collectAsLazyPagingItems()
    val live by presentation.live.collectAsStateWithLifecycle()
    val displayedLive = displayedLiveRows(live, pages.itemSnapshotList.items)
    val refresh = pages.loadState.source.refresh
    if (!rememberHistoryGate(presentation, pages)) {
        PagedTimelineLazyLayout.InitialLoadingView(refresh = refresh, onRetry = pages::retry, modifier = modifier)
        return
    }
    ObserveResidentRows(presentation, pages)
    val listState = key(presentation) { rememberLazyListState() }
    val missingTarget by presentation.missingTarget.collectAsStateWithLifecycle()
    val restoreAnchor = remember(presentation) { presentation.viewport?.takeUnless { it.following } }
    var following by remember(presentation, routeTarget) {
        mutableStateOf(routeTarget == null && restoreAnchor == null)
    }
    val (highlightedTarget, onHighlightTarget) = rememberHighlightedTarget(presentation, routeTarget)

    PagedTimelineEffects.ScrollEffects(
        params = PagedTimelineScrollEffectsParams(
            listState = listState,
            pages = pages,
            live = live,
            presentation = presentation,
            following = following,
            onFollowingChange = { following = it },
        ),
    )
    PagedTimelineEffects.TargetEffects(
        params = PagedTimelineTargetEffectsParams(
            presentation = presentation,
            pages = pages,
            displayedLive = displayedLive,
            listState = listState,
            routeTarget = routeTarget,
            restoreAnchor = restoreAnchor,
            following = following,
            onHighlightTarget = onHighlightTarget,
        ),
    )

    PagedTimelineLazyLayout.Viewport(
        params = PagedTimelineViewportParams(
            presentation = presentation,
            state = state,
            pages = pages,
            displayedLive = displayedLive,
            live = live,
            listState = listState,
            following = following,
            onFollowingChange = { following = it },
            highlightedTarget = highlightedTarget,
            routeTarget = routeTarget,
            missingTarget = missingTarget,
            appearance = appearance,
            callbacks = callbacks,
            modifier = modifier,
        ),
    )
}

@Composable
private fun rememberHistoryGate(
    presentation: ChatPagingPresentation,
    pages: LazyPagingItems<ChatRenderItem>,
): Boolean {
    var initialHistoryReady by remember(presentation) { mutableStateOf(false) }
    val refresh = pages.loadState.source.refresh
    val initialPageAvailable = PagedTimelineLazyLayout.isInitialPageAvailable(pages, refresh)
    if (initialPageAvailable) SideEffect { initialHistoryReady = true }
    return initialHistoryReady || initialPageAvailable
}

@Composable
private fun ObserveResidentRows(
    presentation: ChatPagingPresentation,
    pages: LazyPagingItems<ChatRenderItem>,
) {
    LaunchedEffect(presentation, pages) {
        snapshotFlow { pages.itemSnapshotList.items }.collect { resident ->
            // letta-mobile-x1xnl: this host builds its rows from LazyPagingItems,
            // so it never calls RenderDiagnostics.onRenderItemsBuilt — the hook
            // that ends a render generation. Without a bump here the composed-key
            // set accumulates across every page load and each legitimate
            // re-composition is reported as lazyItem.doubleComposed at WARN.
            RenderDiagnostics.newRenderGeneration()
            presentation.onResidentRows(resident)
        }
    }
}

@Composable
private fun rememberHighlightedTarget(
    presentation: ChatPagingPresentation,
    routeTarget: String?,
): Pair<String?, (String?) -> Unit> {
    var highlightedTarget by remember(presentation, routeTarget) { mutableStateOf<String?>(null) }
    LaunchedEffect(highlightedTarget) {
        if (highlightedTarget != null) {
            kotlinx.coroutines.delay(2_000)
            highlightedTarget = null
        }
    }
    return highlightedTarget to { highlightedTarget = it }
}

internal fun pagedBoundaryDate(newer: ChatRenderItem, older: ChatRenderItem?): LocalDate? {
    if (older == null || newer.boundaryTimestamp.take(10) == older.boundaryTimestamp.take(10)) return null
    return runCatching { LocalDate.parse(newer.boundaryTimestamp.take(10)) }.getOrNull()
}
