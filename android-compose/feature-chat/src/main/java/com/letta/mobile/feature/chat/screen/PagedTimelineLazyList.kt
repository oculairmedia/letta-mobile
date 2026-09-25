package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import com.letta.mobile.data.chat.projection.TimelineRowKeyGuard
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.feature.chat.screen.PagedTimelineEffects.timelinePinchZoom
import com.letta.mobile.feature.chat.screen.messagelist.ChatMessageListLazyContext
import com.letta.mobile.feature.chat.screen.messagelist.ChatMessageListRenderItem
import com.letta.mobile.feature.chat.screen.messagelist.ChatMessageListRenderItemParams
import com.letta.mobile.feature.chat.screen.messagelist.chatMessageListBottomFadeLength
import com.letta.mobile.feature.chat.screen.messagelist.newestMessage
import com.letta.mobile.feature.chat.screen.messagelist.toRenderCallbacks
import com.letta.mobile.ui.chat.render.ChatMessageGeometryState
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.render.toChatRenderItemState
import com.letta.mobile.ui.components.DateSeparator
import com.letta.mobile.ui.components.ScrollToBottomFab
import com.letta.mobile.ui.mascot.MascotLoading
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.theme.chatDimens
import com.letta.mobile.ui.theme.chatShapes
import com.letta.mobile.ui.zoom.PinchScalePreviewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class PagedTimelineLazyListParams(
    val pages: LazyPagingItems<ChatRenderItem>,
    val displayedLive: List<ChatRenderItem>,
    val presentation: ChatPagingPresentation,
    val listState: LazyListState,
    val kineticOverscroll: androidx.compose.foundation.OverscrollEffect?,
    val appearance: ChatContentAppearance,
    val context: ChatMessageListLazyContext,
    val agentId: String?,
)

internal class PagedTimelineViewportParams(
    val presentation: ChatPagingPresentation,
    val state: ChatUiState,
    val pages: LazyPagingItems<ChatRenderItem>,
    val displayedLive: List<ChatRenderItem>,
    val live: List<ChatRenderItem> = emptyList(),
    val listState: LazyListState,
    val following: Boolean,
    val onFollowingChange: (Boolean) -> Unit,
    val highlightedTarget: String?,
    val routeTarget: String?,
    val missingTarget: String?,
    val appearance: ChatContentAppearance,
    val callbacks: ChatContentCallbacks,
    val modifier: Modifier,
)

internal object PagedTimelineLazyLayout {
    fun isInitialPageAvailable(
        pages: LazyPagingItems<ChatRenderItem>,
        refresh: LoadState,
    ): Boolean {
        val hasItems = pages.itemSnapshotList.items.isNotEmpty()
        val endReached = refresh is LoadState.NotLoading &&
            pages.loadState.source.prepend.endOfPaginationReached &&
            pages.loadState.source.append.endOfPaginationReached
        return hasItems || endReached
    }

    @Composable
    fun InitialLoadingView(
        refresh: LoadState,
        onRetry: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Column(modifier) {
            val label = if (refresh is LoadState.Error) "Could not load conversation" else "Loading conversation..."
            Text(label)
            if (refresh is LoadState.Error) {
                TextButton(onClick = onRetry) {
                    Text("Retry")
                }
            }
        }
    }

    @Composable
    fun LazyList(
        params: PagedTimelineLazyListParams,
        modifier: Modifier = Modifier,
    ) {
        val dimens = MaterialTheme.chatDimens
        val openingObserver = LocalTimelineOpeningObserver.current
        val observedModifier = if (openingObserver == null) modifier else modifier.onGloballyPositioned {
            val layout = params.listState.layoutInfo
            // Layout metadata only: no pages[index] reads and therefore no additional load hints.
            openingObserver(TimelineOpeningObservation.Layout(
                rows = layout.visibleItemsInfo.map {
                    TimelineOpeningObservation.VisibleRow(it.key.toString(), it.offset, it.size)
                },
                viewportStart = layout.viewportStartOffset,
                viewportEnd = layout.viewportEndOffset,
            ))
        }
        LazyColumn(
            overscrollEffect = params.kineticOverscroll,
            modifier = observedModifier,
            state = params.listState,
            reverseLayout = true,
            contentPadding = PaddingValues(
                start = dimens.contentPaddingHorizontal,
                end = dimens.contentPaddingHorizontal,
                top = params.appearance.topPadding,
                bottom = params.appearance.bottomPadding,
            ),
        ) {
            timelineRowItems(params)
            timelineLoadingFooter(
                load = params.pages.loadState,
                agentId = params.agentId,
                onRetry = params.pages::retry,
            )
        }
    }

    private fun LazyListScope.timelineRowItems(params: PagedTimelineLazyListParams) {
        val settledKey = params.pages.itemKey { it.key }
        val liveCount = params.displayedLive.size
        // A repeated key would throw inside LazyColumn measurement; the guard turns it into one
        // empty row instead. itemSnapshotList is what itemKey reads, so both agree on every index.
        val duplicates = TimelineRowKeyGuard.duplicateRows(
            params.displayedLive.map { it.key } + params.pages.itemSnapshotList.map { it?.key },
        ) { index -> if (index < liveCount) "live" else "settled" }
        items(
            count = liveCount + params.pages.itemCount,
            key = { index ->
                duplicates[index]
                    ?: params.displayedLive.getOrNull(index)?.key
                    ?: settledKey(index - liveCount)
            },
        ) { index ->
            if (index in duplicates) return@items
            TimelineRowItem(index, params)
        }
    }

    @Composable
    private fun TimelineRowItem(
        index: Int,
        params: PagedTimelineLazyListParams,
    ) {
        val liveRow = params.displayedLive.getOrNull(index)
        val pageIndex = index - params.displayedLive.size
        val row = liveRow ?: params.pages[pageIndex]
        if (row == null) {
            Spacer(Modifier.height(LettaDimens.Orb.railSlotWidth))
            return
        }
        if (liveRow == null) {
            params.presentation.deferredReader(row)?.let { reader ->
                DeferredWindowControls(row.key, reader)
            }
        }
        val older = resolveOlderRow(liveRow, index, params)
        Row(row, index, older, params.context)
    }

    private fun resolveOlderRow(
        liveRow: ChatRenderItem?,
        index: Int,
        params: PagedTimelineLazyListParams,
    ): ChatRenderItem? {
        if (liveRow != null) {
            return params.displayedLive.getOrNull(index + 1)
                ?: if (params.pages.itemCount > 0) params.pages.peek(0) else null
        }
        val pageIndex = index - params.displayedLive.size
        return if (pageIndex + 1 < params.pages.itemCount) params.pages.peek(pageIndex + 1) else null
    }

    private fun LazyListScope.timelineLoadingFooter(
        load: CombinedLoadStates,
        agentId: String?,
        onRetry: () -> Unit,
    ) {
        if (isPagingLoading(load)) {
            item(key = "paging-loading") { MascotLoading(agentId) }
        }
        if (isPagingError(load)) {
            item(key = "paging-retry") { TextButton(onClick = onRetry) { Text("Retry history") } }
        }
    }

    private fun isPagingLoading(load: CombinedLoadStates): Boolean =
        load.refresh is LoadState.Loading || load.append is LoadState.Loading

    private fun isPagingError(load: CombinedLoadStates): Boolean =
        load.refresh is LoadState.Error || load.append is LoadState.Error || load.prepend is LoadState.Error

    @Composable
    fun Row(
        row: ChatRenderItem,
        index: Int,
        older: ChatRenderItem?,
        context: ChatMessageListLazyContext,
    ) {
        val dimens = MaterialTheme.chatDimens
        val shapes = MaterialTheme.chatShapes
        val rowKey = row.key
        val lifecycleObserver = LocalTimelineRowLifecycleObserver.current
        DisposableEffect(rowKey) {
            lifecycleObserver(TimelineRowLifecycle.Mount, rowKey)
            onDispose { lifecycleObserver(TimelineRowLifecycle.Dispose, rowKey) }
        }
        Column {
            DateBoundary(row, older)
            ChatMessageListRenderItem(ChatMessageListRenderItemParams(row, index, context, dimens, shapes))
        }
    }

    @Composable
    fun DateBoundary(newer: ChatRenderItem, older: ChatRenderItem?) {
        pagedBoundaryDate(newer, older)?.let { DateSeparator(date = it) }
    }

    @Composable
    fun Viewport(params: PagedTimelineViewportParams) {
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val dimens = MaterialTheme.chatDimens
        val scope = rememberCoroutineScope()
        val geometry = remember(params.presentation) { ChatMessageGeometryState() }
        val pinch = rememberTimelinePinchController(params.appearance.activeFontScale)
        val currentCallbacks by rememberUpdatedState(params.callbacks)
        val currentActiveScale by rememberUpdatedState(params.appearance.activeFontScale)
        val liveScale = if (pinch.isPinching) pinch.effectiveScale else params.appearance.activeFontScale

        BoxWithConstraints(
            modifier = params.modifier
                .fillMaxSize()
                .timelinePinchZoom(
                    TimelinePinchZoomParams(
                        pinch = pinch,
                        activeFontScale = currentActiveScale,
                        onScaleChange = { scale ->
                            currentCallbacks.onActiveFontScaleChange(scale)
                            currentCallbacks.onFontScaleChange(scale)
                        },
                    ),
                ),
        ) {
            val newestMessage = resolveNewestMessage(
                live = params.live,
                resident = params.pages.itemSnapshotList.items,
            )
            val context = ChatMessageListLazyContext(
                itemState = params.state.toChatRenderItemState(),
                conversationId = resolveConversationId(params.state),
                chatMode = params.appearance.chatMode,
                contentWidthPx = with(density) { (maxWidth - dimens.contentPaddingHorizontal * 2).roundToPx() },
                density = density,
                layoutDirection = direction,
                activeFontScale = params.appearance.activeFontScale,
                liveFontScale = liveScale,
                newestMessageId = newestMessage?.id,
                highlightedMessageId = params.highlightedTarget,
                itemGeometryState = geometry,
                pinchFontScaleController = pinch,
                scaleWindowIndexRange = IntRange.EMPTY,
                callbacks = params.callbacks.toRenderCallbacks(),
            )
            val kineticOverscroll = rememberTimelineKineticOverscrollEffect(
                listState = params.listState,
                pages = params.pages,
                isPinching = pinch.isPinching,
            )
            TimelineFadingList(params, context, kineticOverscroll, newestMessage)
            MissingTargetNotice(
                missingTarget = params.missingTarget,
                routeTarget = params.routeTarget,
                bottomPadding = params.appearance.bottomPadding,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            TimelineScrollToBottom(
                params = params,
                scope = scope,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }

    @Composable
    private fun rememberTimelinePinchController(activeFontScale: Float): PinchScalePreviewController {
        val pinch = remember { PinchScalePreviewController(minScale = 0.7f, maxScale = 1.6f, step = 0.02f) }
        SideEffect { pinch.syncCommittedScale(activeFontScale) }
        return pinch
    }

    @Composable
    private fun TimelineFadingList(
        params: PagedTimelineViewportParams,
        context: ChatMessageListLazyContext,
        kineticOverscroll: androidx.compose.foundation.OverscrollEffect?,
        newestMessage: UiMessage?,
    ) {
        val fadeTargetColor = chatFadeTargetColor(
            chatBackground = params.appearance.chatBackground,
            fallbackContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
        val fadeScrimColor = chatFadeScrimColor(
            chatBackground = params.appearance.chatBackground,
            surfaceColor = MaterialTheme.colorScheme.background,
        )
        val suppressBottomFade = shouldSuppressBottomFade(
            following = params.following,
            role = newestMessage?.role,
            isStreaming = params.state.isStreaming,
        )
        ChatFadingEdgesBox(
            listState = params.listState,
            targetColor = fadeTargetColor,
            scrimColor = fadeScrimColor,
            topPadding = 0.dp,
            topFadeLength = params.appearance.topPadding + ChatFadeEdgeLength,
            bottomFadeLength = chatMessageListBottomFadeLength(params.appearance.bottomPadding),
            suppressBottom = suppressBottomFade,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyList(
                params = PagedTimelineLazyListParams(
                    pages = params.pages,
                    displayedLive = params.displayedLive,
                    presentation = params.presentation,
                    listState = params.listState,
                    kineticOverscroll = kineticOverscroll,
                    appearance = params.appearance,
                    context = context,
                    agentId = params.state.agentId,
                ),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    @Composable
    private fun TimelineScrollToBottom(
        params: PagedTimelineViewportParams,
        scope: CoroutineScope,
        modifier: Modifier = Modifier,
    ) {
        ScrollToBottomFab(
            visible = shouldShowNewestAffordance(
                isAnchoredAwayFromTail = params.presentation.isAnchoredAwayFromTail,
                canScrollTowardNewest = params.listState.canScrollBackward,
            ),
            onClick = {
                onScrollToBottomClick(
                    presentation = params.presentation,
                    listState = params.listState,
                    onFollowingChange = params.onFollowingChange,
                    scope = scope,
                )
            },
            modifier = modifier.padding(
                end = LettaSpacing.INNER_PADDING,
                bottom = LettaSpacing.INNER_PADDING + params.appearance.bottomPadding,
            ),
        )
    }

    private fun resolveNewestMessage(
        live: List<ChatRenderItem>,
        resident: List<ChatRenderItem>,
    ): UiMessage? = live.firstOrNull()?.newestMessage() ?: resident.firstOrNull()?.newestMessage()

    private fun resolveConversationId(state: ChatUiState): String? =
        (state.conversationState as? ConversationState.Ready)?.conversationId

    @Composable
    private fun rememberTimelineKineticOverscrollEffect(
        listState: LazyListState,
        pages: LazyPagingItems<ChatRenderItem>,
        isPinching: Boolean,
    ): androidx.compose.foundation.OverscrollEffect? {
        val reducedMotion = com.letta.mobile.ui.components.rememberReducedMotionEnabled()
        val kineticOverscroll = rememberTimelineKineticOverscroll(
            enabled = !reducedMotion && !isPinching && pages.loadState.refresh !is LoadState.Loading,
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
        return kineticOverscroll
    }

    private fun shouldSuppressBottomFade(
        following: Boolean,
        role: String?,
        isStreaming: Boolean,
    ): Boolean = following && role == "user" && isStreaming

    @Composable
    private fun MissingTargetNotice(
        missingTarget: String?,
        routeTarget: String?,
        bottomPadding: Dp,
        modifier: Modifier = Modifier,
    ) {
        if (missingTarget != null && missingTarget == routeTarget) {
            Column(modifier.padding(bottom = bottomPadding)) {
                Text("Message not found")
            }
        }
    }

    private fun onScrollToBottomClick(
        presentation: ChatPagingPresentation,
        listState: LazyListState,
        onFollowingChange: (Boolean) -> Unit,
        scope: CoroutineScope,
    ) {
        if (presentation.isAnchoredAwayFromTail) {
            presentation.requestTail()
        } else {
            scope.launch {
                listState.scrollToItem(0)
                onFollowingChange(true)
            }
        }
    }
}
