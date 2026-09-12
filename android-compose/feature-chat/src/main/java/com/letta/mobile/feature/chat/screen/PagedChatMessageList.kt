package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.ui.components.DateSeparator
import com.letta.mobile.ui.components.ScrollToBottomFab
import com.letta.mobile.ui.theme.LettaSpacing
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.letta.mobile.feature.chat.screen.messagelist.*
import com.letta.mobile.ui.chat.render.ChatMessageGeometryState
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.toChatRenderItemState
import com.letta.mobile.ui.theme.chatDimens
import com.letta.mobile.ui.theme.chatShapes
import com.letta.mobile.ui.zoom.PinchScalePreviewController

internal fun followNewestEdge(wasScrolling: Boolean, atNewestEdge: Boolean, prependExhausted: Boolean): Boolean =
    wasScrolling && atNewestEdge && prependExhausted

internal fun shouldRepositionAfterPagerRefresh(refresh: LoadState): Boolean =
    refresh is LoadState.Loading

internal fun residentTargetIndex(
    rows: List<com.letta.mobile.data.chat.projection.ChatRenderItem>,
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
    LaunchedEffect(presentation, pages) {
        snapshotFlow { pages.itemSnapshotList.items }.collect { resident ->
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
    LaunchedEffect(listState, pages.loadState.prepend.endOfPaginationReached) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) following = false
            else if (followNewestEdge(wasScrolling, !listState.canScrollBackward, pages.loadState.prepend.endOfPaginationReached)) {
                following = true
            }
            wasScrolling = scrolling
        }
    }
    var previousLiveUser by remember(presentation) {
        mutableStateOf((live.firstOrNull() as? ChatRenderItem.Single)?.message?.id)
    }
    LaunchedEffect(live) {
        val newest = (live.firstOrNull() as? ChatRenderItem.Single)?.message
        if (newest?.role == "user" && newest.id != previousLiveUser) {
            if (presentation.isAnchoredAwayFromTail) presentation.requestTail()
            else {
                listState.scrollToItem(0)
                following = true
            }
        }
        previousLiveUser = newest?.id
    }
    LaunchedEffect(live, pages.itemSnapshotList, following) {
        if (following && !listState.isScrollInProgress) listState.scrollToItem(0)
    }
    var targetPositioned by remember(presentation, routeTarget) { mutableStateOf(false) }
    LaunchedEffect(pages.loadState.refresh) {
        if (shouldRepositionAfterPagerRefresh(pages.loadState.refresh)) targetPositioned = false
    }
    var highlightedTarget by remember(presentation, routeTarget) { mutableStateOf<String?>(null) }
    LaunchedEffect(highlightedTarget) {
        if (highlightedTarget != null) {
            kotlinx.coroutines.delay(2_000)
            highlightedTarget = null
        }
    }
    LaunchedEffect(presentation, routeTarget, pages.itemSnapshotList, live) {
        val target = routeTarget ?: restoreAnchor?.messageId ?: return@LaunchedEffect
        if (!targetPositioned) {
            // The engine selects an around-target window. Inspect only resident rows;
            // never trigger sequential history loads to search for an absent target.
            val snapshot = pages.itemSnapshotList
            val index = live.indexOfFirst { it.containsMessageId(target) }.takeIf { it >= 0 }
                ?: residentTargetIndex(snapshot.items, target, live.size, snapshot.placeholdersBefore)
            if (index != null) {
                if (routeTarget == null) {
                    listState.scrollToItem(index, restoreAnchor?.offset ?: 0)
                } else {
                    listState.scrollToItem(index)
                    val item = snapshotFlow {
                        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                    }.first { it != null }!!
                    val layout = listState.layoutInfo
                    val centerOffset = ((layout.viewportEndOffset - layout.viewportStartOffset - item.size) / 2)
                        .coerceAtLeast(0)
                    listState.scrollToItem(index, -centerOffset)
                    highlightedTarget = target
                }
                targetPositioned = true
            }
        }
    }
    LaunchedEffect(presentation, missingTarget) {
        if (routeTarget == null && restoreAnchor != null && missingTarget == restoreAnchor.messageId) {
            presentation.requestTail()
        }
    }
    LaunchedEffect(presentation, targetPositioned, live, pages.itemSnapshotList, following) {
        if (!targetPositioned && (restoreAnchor != null || routeTarget != null)) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                val row = live.getOrNull(index) ?: (index - live.size).takeIf { it in 0 until pages.itemCount }
                    ?.let { pages.peek(it) }
                val messageId = when (row) {
                    is ChatRenderItem.Single -> row.message.id
                    is ChatRenderItem.RunBlock -> row.messages.lastOrNull()?.first?.id
                    else -> null
                }
                if (messageId != null) presentation.saveViewport(ChatPagingViewport(messageId, offset, following))
            }
    }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val dimens = MaterialTheme.chatDimens
    val shapes = MaterialTheme.chatShapes
    val geometry = remember(presentation) { ChatMessageGeometryState() }
    val pinch = remember { PinchScalePreviewController(minScale = 0.7f, maxScale = 1.6f, step = 0.02f) }
    SideEffect { pinch.syncCommittedScale(appearance.activeFontScale) }
    val currentCallbacks by rememberUpdatedState(callbacks)
    val liveScale = if (pinch.isPinching) pinch.effectiveScale else appearance.activeFontScale
    BoxWithConstraints(modifier.fillMaxSize().pointerInput(pinch, appearance.activeFontScale) {
        try {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } >= 2) {
                        if (!pinch.isPinching) pinch.begin(appearance.activeFontScale)
                        pinch.applyZoom(event.calculateZoom())
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
                if (pinch.isPinching) {
                    val scale = pinch.finishPreview()
                    currentCallbacks.onActiveFontScaleChange(scale)
                    currentCallbacks.onFontScaleChange(scale)
                }
            }
        } finally {
            pinch.cancel()
        }
    }) {
        val context = ChatMessageListLazyContext(
            itemState = state.toChatRenderItemState(),
            conversationId = (state.conversationState as? com.letta.mobile.ui.chat.render.ConversationState.Ready)?.conversationId,
            chatMode = appearance.chatMode,
            contentWidthPx = with(density) { (maxWidth - dimens.contentPaddingHorizontal * 2).roundToPx() },
            density = density,
            layoutDirection = direction,
            activeFontScale = appearance.activeFontScale,
            liveFontScale = liveScale,
            newestMessageId = when (val newest = live.firstOrNull() ?: pages.itemSnapshotList.items.firstOrNull()) {
                is ChatRenderItem.Single -> newest.message.id
                is ChatRenderItem.RunBlock -> newest.messages.lastOrNull()?.first?.id
                else -> null
            },
            highlightedMessageId = highlightedTarget,
            itemGeometryState = geometry,
            pinchFontScaleController = pinch,
            scaleWindowIndexRange = IntRange.EMPTY,
            callbacks = ChatMessageRenderCallbacks(
                callbacks.onSendMessage, callbacks.onRerunMessage, callbacks.onSubmitApproval,
                callbacks.onToggleRunCollapsed, callbacks.onToggleReasoningExpanded, callbacks.onAttachmentImageTap,
            ),
        )
        val reducedMotion = com.letta.mobile.ui.components.rememberReducedMotionEnabled()
        val elasticEffect = ir.farsroidx.overscroll.rememberVerticalElasticOverscroll(
            maxStretchRatio = 8,
            springDampingRatio = 0.85f,
            lockedEdge = if (pages.loadState.append.endOfPaginationReached) null
                else ir.farsroidx.overscroll.ElasticOverscrollEdge.TOP,
        )
        LazyColumn(
            overscrollEffect = if (reducedMotion || pinch.isPinching ||
                pages.loadState.refresh is LoadState.Loading) null else elasticEffect,
            modifier = Modifier.fillMaxSize().padding(top = appearance.topPadding),
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(start = dimens.contentPaddingHorizontal, end = dimens.contentPaddingHorizontal,
                top = 0.dp, bottom = appearance.bottomPadding),
        ) {
            items(live.size, key = { live[it].key }) { index ->
                Column {
                    PagedDateBoundary(live[index], live.getOrNull(index + 1)
                        ?: if (pages.itemCount > 0) pages.peek(0) else null)
                    ChatMessageListRenderItem(ChatMessageListRenderItemParams(live[index], index, context, dimens, shapes))
                }
            }
            items(pages.itemCount, key = pages.itemKey { it.key }) { index ->
                val row = pages[index]
                if (row != null) {
                    presentation.deferredReader(row)?.let { reader -> DeferredWindowControls(row.key, reader) }
                    Column {
                        PagedDateBoundary(row, if (index + 1 < pages.itemCount) pages.peek(index + 1) else null)
                        ChatMessageListRenderItem(ChatMessageListRenderItemParams(row, live.size + index, context, dimens, shapes))
                    }
                } else {
                    Spacer(Modifier.height(48.dp))
                }
            }
            val load = pages.loadState
            if (load.refresh is LoadState.Loading || load.append is LoadState.Loading) {
                item(key = "paging-loading") { CircularProgressIndicator() }
            }
            if (load.refresh is LoadState.Error || load.append is LoadState.Error || load.prepend is LoadState.Error) {
                item(key = "paging-retry") { TextButton(onClick = pages::retry) { Text("Retry history") } }
            }
        }
        if (missingTarget != null && missingTarget == routeTarget) {
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = appearance.bottomPadding)) {
                Text("Message not found")
            }
        }
        // The same affordance the non-paged list shows, placed the same way. A bare text button
        // here had no chrome of its own, so it read as loose text floating over the conversation.
        ScrollToBottomFab(
            visible = !following,
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

@Composable
private fun PagedDateBoundary(newer: ChatRenderItem, older: ChatRenderItem?) {
    pagedBoundaryDate(newer, older)?.let { DateSeparator(date = it) }
}
