package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.feature.chat.screen.messagelist.*
import com.letta.mobile.ui.chat.render.ChatMessageGeometryState
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.render.RenderDiagnostics
import com.letta.mobile.ui.chat.render.toChatRenderItemState
import com.letta.mobile.ui.components.ScrollToBottomFab
import com.letta.mobile.ui.mascot.MascotLoading
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.theme.chatDimens
import com.letta.mobile.ui.zoom.PinchScalePreviewController
import kotlinx.coroutines.launch
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
            androidx.compose.material3.Text(presentation.openError ?: "Opening conversation...")
            if (presentation.openError != null) androidx.compose.material3.TextButton(onClick = presentation.retryOpen) {
                androidx.compose.material3.Text("Retry")
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
    val residentRows = pages.itemSnapshotList.items
    val displayedLive = displayedLiveRows(live, residentRows)
    var initialHistoryReady by remember(presentation) { mutableStateOf(false) }
    val refresh = pages.loadState.source.refresh
    val initialPageAvailable = isInitialPageAvailable(pages, refresh)
    if (initialPageAvailable) SideEffect { initialHistoryReady = true }
    // Do not paint an optimistic-only conversation before its first history page.
    // Once visible, keep the viewport mounted through all later refreshes.
    if (!initialHistoryReady && !initialPageAvailable) {
        PagedChatInitialLoadingView(refresh = refresh, onRetry = pages::retry, modifier = modifier)
        return
    }
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
    val listState = key(presentation) { rememberLazyListState() }
    val missingTarget by presentation.missingTarget.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val restoreAnchor = remember(presentation) { presentation.viewport?.takeUnless { it.following } }
    var following by remember(presentation, routeTarget) {
        mutableStateOf(routeTarget == null && restoreAnchor == null)
    }
    var highlightedTarget by remember(presentation, routeTarget) { mutableStateOf<String?>(null) }
    LaunchedEffect(highlightedTarget) {
        if (highlightedTarget != null) {
            kotlinx.coroutines.delay(2_000)
            highlightedTarget = null
        }
    }

    PagedTimelineScrollEffects(
        params = PagedTimelineScrollEffectsParams(
            listState = listState,
            pages = pages,
            live = live,
            presentation = presentation,
            following = following,
            onFollowingChange = { following = it },
        ),
    )
    PagedTimelineTargetEffects(
        params = PagedTimelineTargetEffectsParams(
            presentation = presentation,
            pages = pages,
            displayedLive = displayedLive,
            listState = listState,
            routeTarget = routeTarget,
            restoreAnchor = restoreAnchor,
            following = following,
            onHighlightTarget = { highlightedTarget = it },
        ),
    )

    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val dimens = MaterialTheme.chatDimens
    val geometry = remember(presentation) { ChatMessageGeometryState() }
    val pinch = remember { PinchScalePreviewController(minScale = 0.7f, maxScale = 1.6f, step = 0.02f) }
    SideEffect { pinch.syncCommittedScale(appearance.activeFontScale) }
    val currentCallbacks by rememberUpdatedState(callbacks)
    val currentActiveScale by rememberUpdatedState(appearance.activeFontScale)
    val liveScale = if (pinch.isPinching) pinch.effectiveScale else appearance.activeFontScale
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .pagedTimelinePinchZoom(
                pinch = pinch,
                activeFontScale = currentActiveScale,
                onScaleChange = { scale ->
                    currentCallbacks.onActiveFontScaleChange(scale)
                    currentCallbacks.onFontScaleChange(scale)
                },
            ),
    ) {
        val newestMessage = (live.firstOrNull() ?: pages.itemSnapshotList.items.firstOrNull())?.newestMessage()
        val context = ChatMessageListLazyContext(
            itemState = state.toChatRenderItemState(),
            conversationId = (state.conversationState as? ConversationState.Ready)?.conversationId,
            chatMode = appearance.chatMode,
            contentWidthPx = with(density) { (maxWidth - dimens.contentPaddingHorizontal * 2).roundToPx() },
            density = density,
            layoutDirection = direction,
            activeFontScale = appearance.activeFontScale,
            liveFontScale = liveScale,
            newestMessageId = newestMessage?.id,
            highlightedMessageId = highlightedTarget,
            itemGeometryState = geometry,
            pinchFontScaleController = pinch,
            scaleWindowIndexRange = IntRange.EMPTY,
            callbacks = callbacks.toRenderCallbacks(),
        )
        val reducedMotion = com.letta.mobile.ui.components.rememberReducedMotionEnabled()
        val kineticOverscroll = rememberTimelineKineticOverscroll(
            enabled = !reducedMotion && !pinch.isPinching &&
                pages.loadState.refresh !is LoadState.Loading,
            canFlingPastPositiveEdge = {
                !listState.canScrollForward && pages.loadState.append.endOfPaginationReached
            },
            canFlingPastNegativeEdge = {
                !listState.canScrollBackward && pages.loadState.prepend.endOfPaginationReached
            },
        )
        DisposableEffect(kineticOverscroll) {
            onDispose(kineticOverscroll::cancelAndClear)
        }
        val fadeTargetColor = chatFadeTargetColor(
            chatBackground = appearance.chatBackground,
            fallbackContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
        val fadeScrimColor = chatFadeScrimColor(
            chatBackground = appearance.chatBackground,
            surfaceColor = MaterialTheme.colorScheme.background,
        )
        val topFadeLength = appearance.topPadding + ChatFadeEdgeLength
        val bottomFadeLength = chatMessageListBottomFadeLength(appearance.bottomPadding)
        val newestRole = newestMessage?.role
        // Paging can briefly report a backward scroll range while the live tail settles.
        // Keep a streaming user prompt at the newest edge fully visible during that window.
        val suppressBottomFade = following && newestRole == "user" && state.isStreaming
        ChatFadingEdgesBox(
            listState = listState,
            targetColor = fadeTargetColor,
            scrimColor = fadeScrimColor,
            topPadding = 0.dp,
            topFadeLength = topFadeLength,
            bottomFadeLength = bottomFadeLength,
            suppressBottom = suppressBottomFade,
            // Keep the viewport behind the header; only resting content needs its inset.
            modifier = Modifier.fillMaxSize(),
        ) {
            PagedTimelineLazyList(
                params = PagedTimelineLazyListParams(
                    pages = pages,
                    displayedLive = displayedLive,
                    presentation = presentation,
                    listState = listState,
                    kineticOverscroll = kineticOverscroll,
                    appearance = appearance,
                    context = context,
                    agentId = state.agentId,
                ),
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (missingTarget != null && missingTarget == routeTarget) {
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = appearance.bottomPadding)) {
                Text("Message not found")
            }
        }
        // The same affordance the non-paged list shows, placed the same way. A bare text button
        // here had no chrome of its own, so it read as loose text floating over the conversation.
        ScrollToBottomFab(
            visible = shouldShowNewestAffordance(
                isAnchoredAwayFromTail = presentation.isAnchoredAwayFromTail,
                canScrollTowardNewest = listState.canScrollBackward,
            ),
            onClick = {
                if (presentation.isAnchoredAwayFromTail) presentation.requestTail()
                else scope.launch {
                    listState.scrollToItem(0)
                    following = true
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = LettaSpacing.INNER_PADDING,
                    bottom = LettaSpacing.INNER_PADDING + appearance.bottomPadding,
                ),
        )
    }
}

internal fun pagedBoundaryDate(newer: ChatRenderItem, older: ChatRenderItem?): LocalDate? {
    if (older == null || newer.boundaryTimestamp.take(10) == older.boundaryTimestamp.take(10)) return null
    return runCatching { LocalDate.parse(newer.boundaryTimestamp.take(10)) }.getOrNull()
}
