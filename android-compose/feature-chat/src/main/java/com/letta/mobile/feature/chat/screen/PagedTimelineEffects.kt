package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.ItemSnapshotList
import androidx.paging.compose.LazyPagingItems
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.feature.chat.screen.messagelist.newestMessage
import com.letta.mobile.ui.chat.render.RenderDiagnostics
import com.letta.mobile.ui.zoom.PinchScalePreviewController
import kotlinx.coroutines.flow.first

internal class PagedTimelineScrollEffectsParams(
    val listState: LazyListState,
    val pages: LazyPagingItems<ChatRenderItem>,
    val live: List<ChatRenderItem>,
    val presentation: ChatPagingPresentation,
    val following: Boolean,
    val onFollowingChange: (Boolean) -> Unit,
)

internal class PagedTimelineTargetEffectsParams(
    val presentation: ChatPagingPresentation,
    val pages: LazyPagingItems<ChatRenderItem>,
    val displayedLive: List<ChatRenderItem>,
    val listState: LazyListState,
    val routeTarget: String?,
    val restoreAnchor: ChatPagingViewport?,
    val following: Boolean,
    val onHighlightTarget: (String?) -> Unit,
)

internal object PagedTimelineEffects {
    @Composable
    fun ScrollEffects(params: PagedTimelineScrollEffectsParams) {
        LaunchedEffect(params.listState) {
            params.listState.interactionSource.interactions.collect { interaction ->
                if (interaction is DragInteraction.Start) params.onFollowingChange(false)
            }
        }
        observeScrollSettlement(params.listState, params.pages.loadState.prepend.endOfPaginationReached, params.onFollowingChange)
        LaunchedEffect(params.live) { RenderDiagnostics.newRenderGeneration() }
        observeLiveUserPrompt(params.live, params.presentation, params.listState, params.onFollowingChange)
        LaunchedEffect(params.live, params.pages.itemSnapshotList, params.following) {
            if (params.following && !params.listState.isScrollInProgress) {
                params.listState.scrollToItem(0)
            }
        }
    }

    @Composable
    private fun observeScrollSettlement(
        listState: LazyListState,
        prependDone: Boolean,
        onFollowingChange: (Boolean) -> Unit,
    ) {
        LaunchedEffect(listState, prependDone) {
            var wasScrolling = false
            snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
                val atEdge = !listState.canScrollBackward
                if (!scrolling && followNewestEdge(wasScrolling, atEdge, prependDone)) {
                    onFollowingChange(true)
                }
                wasScrolling = scrolling
            }
        }
    }

    @Composable
    private fun observeLiveUserPrompt(
        live: List<ChatRenderItem>,
        presentation: ChatPagingPresentation,
        listState: LazyListState,
        onFollowingChange: (Boolean) -> Unit,
    ) {
        var previousLiveUser by remember(presentation) {
            mutableStateOf((live.firstOrNull() as? ChatRenderItem.Single)?.message?.id)
        }
        LaunchedEffect(live) {
            val newest = (live.firstOrNull() as? ChatRenderItem.Single)?.message
            if (newest?.role == "user" && newest.id != previousLiveUser) {
                if (presentation.isAnchoredAwayFromTail) {
                    presentation.requestTail()
                } else {
                    listState.scrollToItem(0)
                    onFollowingChange(true)
                }
            }
            previousLiveUser = newest?.id
        }
    }

    @Composable
    fun TargetEffects(params: PagedTimelineTargetEffectsParams) {
        var targetPositioned by remember(params.presentation, params.routeTarget) { mutableStateOf(false) }
        val missingTarget by params.presentation.missingTarget.collectAsStateWithLifecycle()

        LaunchedEffect(params.pages.loadState.refresh) {
            if (shouldRepositionAfterPagerRefresh(params.pages.loadState.refresh)) targetPositioned = false
        }
        LaunchedEffect(params.presentation, params.routeTarget, params.pages.itemSnapshotList, params.displayedLive) {
            val target = params.routeTarget ?: params.restoreAnchor?.messageId ?: return@LaunchedEffect
            if (!targetPositioned) {
                val index = resolveTargetScrollPosition(target, params.displayedLive, params.pages.itemSnapshotList)
                if (index != null) {
                    applyTargetScroll(params, index, target)
                    targetPositioned = true
                }
            }
        }
        LaunchedEffect(params.presentation, missingTarget) {
            if (shouldRestoreMissingTail(params.routeTarget, params.restoreAnchor, missingTarget)) {
                params.presentation.requestTail()
            }
        }
        LaunchedEffect(params.presentation, targetPositioned, params.displayedLive, params.pages.itemSnapshotList, params.following) {
            if (!canSaveViewport(targetPositioned, params.restoreAnchor, params.routeTarget)) return@LaunchedEffect
            snapshotFlow { params.listState.firstVisibleItemIndex to params.listState.firstVisibleItemScrollOffset }
                .collect { (index, offset) ->
                    val messageId = resolveFirstVisibleMessageId(index, params.displayedLive, params.pages)
                    if (messageId != null) params.presentation.saveViewport(ChatPagingViewport(messageId, offset, params.following))
                }
        }
    }

    private suspend fun applyTargetScroll(
        params: PagedTimelineTargetEffectsParams,
        index: Int,
        target: String,
    ) {
        if (params.routeTarget == null) {
            params.listState.scrollToItem(index, params.restoreAnchor?.offset ?: 0)
        } else {
            params.listState.scrollToItem(index)
            val item = snapshotFlow {
                params.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            }.first { it != null }!!
            val layout = params.listState.layoutInfo
            val centerOffset = ((layout.viewportEndOffset - layout.viewportStartOffset - item.size) / 2)
                .coerceAtLeast(0)
            params.listState.scrollToItem(index, -centerOffset)
            params.onHighlightTarget(target)
        }
    }

    private fun shouldRestoreMissingTail(
        routeTarget: String?,
        restoreAnchor: ChatPagingViewport?,
        missingTarget: String?,
    ): Boolean = routeTarget == null && restoreAnchor != null && missingTarget == restoreAnchor.messageId

    private fun canSaveViewport(
        targetPositioned: Boolean,
        restoreAnchor: ChatPagingViewport?,
        routeTarget: String?,
    ): Boolean = targetPositioned || (restoreAnchor == null && routeTarget == null)

    fun resolveTargetScrollPosition(
        target: String,
        displayedLive: List<ChatRenderItem>,
        snapshot: ItemSnapshotList<ChatRenderItem>,
    ): Int? {
        val visibleLiveIndex = displayedLive.indexOfFirst { it.containsMessageId(target) }.takeIf { it >= 0 }
        return visibleLiveIndex
            ?: residentTargetIndex(snapshot.items, target, displayedLive.size, snapshot.placeholdersBefore)
    }

    fun resolveFirstVisibleMessageId(
        index: Int,
        displayedLive: List<ChatRenderItem>,
        pages: LazyPagingItems<ChatRenderItem>,
    ): String? {
        val row = displayedLive.getOrNull(index)
            ?: (index - displayedLive.size).takeIf { it in 0 until pages.itemCount }?.let { pages.peek(it) }
        return row?.newestMessage()?.id
    }

    fun Modifier.timelinePinchZoom(
        pinch: PinchScalePreviewController,
        activeFontScale: Float,
        onScaleChange: (Float) -> Unit,
    ): Modifier {
        return pointerInput(pinch) {
            try {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            if (!pinch.isPinching) pinch.begin(activeFontScale)
                            pinch.applyZoom(event.calculateZoom())
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (pinch.isPinching) {
                        val scale = pinch.finishPreview()
                        onScaleChange(scale)
                    }
                }
            } finally {
                pinch.cancel()
            }
        }
    }
}
