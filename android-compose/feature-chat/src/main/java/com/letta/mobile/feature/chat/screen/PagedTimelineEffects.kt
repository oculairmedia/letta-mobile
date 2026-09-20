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
) {
    fun shouldRestoreMissingTail(missingTarget: String?): Boolean =
        routeTarget == null && restoreAnchor != null && missingTarget == restoreAnchor.messageId

    fun canSaveViewport(targetPositioned: Boolean): Boolean =
        targetPositioned || (restoreAnchor == null && routeTarget == null)
}

internal class TimelinePinchZoomParams(
    val pinch: PinchScalePreviewController,
    val activeFontScale: Float,
    val onScaleChange: (Float) -> Unit,
)

internal object PagedTimelineEffects {
    @Composable
    fun ScrollEffects(params: PagedTimelineScrollEffectsParams) {
        LaunchedEffect(params.listState) {
            params.listState.interactionSource.interactions.collect { interaction ->
                if (interaction is DragInteraction.Start) params.onFollowingChange(false)
            }
        }
        observeScrollSettlement(params)
        LaunchedEffect(params.live) { RenderDiagnostics.newRenderGeneration() }
        observeLiveUserPrompt(params)
        LaunchedEffect(params.live, params.pages.itemSnapshotList, params.following) {
            if (params.following && !params.listState.isScrollInProgress) {
                params.listState.scrollToItem(0)
            }
        }
    }

    @Composable
    private fun observeScrollSettlement(params: PagedTimelineScrollEffectsParams) {
        val listState = params.listState
        val prependDone = params.pages.loadState.prepend.endOfPaginationReached
        LaunchedEffect(listState, prependDone) {
            var wasScrolling = false
            snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
                val atEdge = !listState.canScrollBackward
                if (!scrolling && followNewestEdge(wasScrolling, atEdge, prependDone)) {
                    params.onFollowingChange(true)
                }
                wasScrolling = scrolling
            }
        }
    }

    @Composable
    private fun observeLiveUserPrompt(params: PagedTimelineScrollEffectsParams) {
        var previousLiveUser by remember(params.presentation) {
            mutableStateOf((params.live.firstOrNull() as? ChatRenderItem.Single)?.message?.id)
        }
        LaunchedEffect(params.live) {
            val newest = (params.live.firstOrNull() as? ChatRenderItem.Single)?.message
            if (newest?.role == "user" && newest.id != previousLiveUser) {
                if (params.presentation.isAnchoredAwayFromTail) {
                    params.presentation.requestTail()
                } else {
                    params.listState.scrollToItem(0)
                    params.onFollowingChange(true)
                }
            }
            previousLiveUser = newest?.id
        }
    }

    @Composable
    fun TargetEffects(params: PagedTimelineTargetEffectsParams) {
        val targetPositioned = remember(params.presentation, params.routeTarget) { mutableStateOf(false) }
        val missingTarget by params.presentation.missingTarget.collectAsStateWithLifecycle()

        LaunchedEffect(params.pages.loadState.refresh) {
            if (shouldRepositionAfterPagerRefresh(params.pages.loadState.refresh)) targetPositioned.value = false
        }
        observeTargetPositioning(params, targetPositioned)
        LaunchedEffect(params.presentation, missingTarget) {
            if (params.shouldRestoreMissingTail(missingTarget)) {
                params.presentation.requestTail()
            }
        }
        observeViewportSaving(params, targetPositioned)
    }

    @Composable
    private fun observeTargetPositioning(
        params: PagedTimelineTargetEffectsParams,
        targetPositioned: androidx.compose.runtime.MutableState<Boolean>,
    ) {
        LaunchedEffect(params.presentation, params.routeTarget, params.pages.itemSnapshotList, params.displayedLive) {
            val target = params.routeTarget ?: params.restoreAnchor?.messageId ?: return@LaunchedEffect
            if (!targetPositioned.value) {
                val index = resolveTargetScrollPosition(target, params.displayedLive, params.pages.itemSnapshotList)
                if (index != null) {
                    applyTargetScroll(params, index)
                    targetPositioned.value = true
                }
            }
        }
    }

    @Composable
    private fun observeViewportSaving(
        params: PagedTimelineTargetEffectsParams,
        targetPositioned: androidx.compose.runtime.MutableState<Boolean>,
    ) {
        LaunchedEffect(params.presentation, targetPositioned.value, params.displayedLive, params.pages.itemSnapshotList, params.following) {
            if (!params.canSaveViewport(targetPositioned.value)) return@LaunchedEffect
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
    ) {
        val target = params.routeTarget ?: params.restoreAnchor?.messageId ?: return
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

    fun Modifier.timelinePinchZoom(params: TimelinePinchZoomParams): Modifier {
        return pointerInput(params.pinch, params.activeFontScale) {
            try {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            if (!params.pinch.isPinching) params.pinch.begin(params.activeFontScale)
                            params.pinch.applyZoom(event.calculateZoom())
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (params.pinch.isPinching) {
                        val scale = params.pinch.finishPreview()
                        params.onScaleChange(scale)
                    }
                }
            } finally {
                params.pinch.cancel()
            }
        }
    }
}
