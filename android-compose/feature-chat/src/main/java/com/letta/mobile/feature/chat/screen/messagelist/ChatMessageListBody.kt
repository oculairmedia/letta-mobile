package com.letta.mobile.feature.chat.screen.messagelist

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.feature.chat.screen.LocalChatShouldDeferHeavyToolCards
import com.letta.mobile.feature.chat.screen.chatFadeTargetColor
import com.letta.mobile.feature.chat.screen.ChatFadeEdgeLength
import com.letta.mobile.feature.chat.screen.ChatFadingEdgesBox
import com.letta.mobile.ui.chat.render.ChatMessageGeometryState
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.chatGeometrySignature
import com.letta.mobile.ui.components.ScrollToBottomFab
import com.letta.mobile.ui.theme.ChatBackground
import com.letta.mobile.ui.theme.LocalChatFontScale
import com.letta.mobile.ui.theme.LocalChatIsPinching
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.theme.chatDimens
import com.letta.mobile.ui.theme.chatShapes
import com.letta.mobile.ui.zoom.PinchScalePreviewController
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.derivedStateOf
import com.letta.mobile.data.chat.runtime.ChatViewportFollowPolicy
import com.letta.mobile.feature.chat.screen.toChatViewportSnapshot
import kotlinx.coroutines.CoroutineScope

@Composable
internal fun ChatMessageListPinchGestureBox(
    listState: LazyListState,
    activeFontScale: Float,
    currentRenderItems: List<ChatRenderItem>,
    currentLoadPressureSummary: ChatLoadPressureSummary,
    callbacks: ChatMessageListCallbacks,
    pinchFontScaleController: PinchScalePreviewController,
    pinchFrameBudgetSampler: ChatPinchFrameBudgetSampler,
    onPinchTick: (Long) -> Unit,
    onPinchAnimationSuppressionTick: (Long) -> Unit,
    onSuppressPinchLayoutAnimations: (Boolean) -> Unit,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var gesturePinching = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val activePointers = event.changes.filter { it.pressed }
                        if (activePointers.size >= 2) {
                            if (!gesturePinching) {
                                gesturePinching = true
                                onSuppressPinchLayoutAnimations(true)
                                onPinchAnimationSuppressionTick(0L)
                                pinchFontScaleController.begin(activeFontScale)
                                val renderItemsByKey = currentRenderItems.associateBy { it.key }
                                val visibleRenderItems = listState.layoutInfo.visibleItemsInfo.mapNotNull { itemInfo ->
                                    renderItemsByKey[itemInfo.key]
                                }
                                val visibleContent = visibleRenderItems.pinchVisibleContentSummary()
                                val loadPressureForSample = currentLoadPressureSummary.copy(
                                    toolCardCount = currentRenderItems.pinchVisibleContentSummary().toolCards,
                                )
                                pinchFrameBudgetSampler.start(
                                    visibleItems = listState.layoutInfo.visibleItemsInfo.size,
                                    totalItems = listState.layoutInfo.totalItemsCount,
                                    visibleUserMessages = visibleContent.userMessages,
                                    visibleAssistantMessages = visibleContent.assistantMessages,
                                    visibleToolCards = visibleContent.toolCards,
                                    visibleRunBlocks = visibleContent.runBlocks,
                                    loadPressure = loadPressureForSample,
                                    committedScale = activeFontScale,
                                )
                                onPinchTick(System.nanoTime())
                            }
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                event.changes.forEach { it.consume() }
                                pinchFontScaleController.applyZoom(zoom)
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (gesturePinching) {
                        val snapped = pinchFontScaleController.finishPreview()
                        callbacks.onActiveFontScaleChange(snapped)
                        callbacks.onFontScaleChange(snapped)
                        pinchFrameBudgetSampler.stop(committedScale = activeFontScale, targetScale = snapped)
                        onPinchTick(System.nanoTime())
                        onPinchAnimationSuppressionTick(System.nanoTime())
                    } else {
                        pinchFontScaleController.cancel()
                        pinchFrameBudgetSampler.cancel()
                        onSuppressPinchLayoutAnimations(false)
                    }
                }
            },
    ) {
        content()
    }
}

@Composable
internal fun ChatMessageListBody(
    state: ChatUiState,
    renderItems: List<ChatRenderItem>,
    appearance: ChatMessageListAppearance,
    callbacks: ChatMessageListCallbacks,
    listState: LazyListState,
    isUserScrolling: Boolean,
    liveFontScale: Float,
    pinchFontScaleController: PinchScalePreviewController,
    scaleWindowIndexRange: IntRange,
    itemGeometryState: ChatMessageGeometryState,
    highlightedMessageId: String?,
    showScrollFab: Boolean,
    suppressPinchLayoutAnimations: Boolean,
    onScrollToBottom: () -> Unit,
    showFontIndicator: Boolean,
    modifier: Modifier = Modifier,
) {
    val chatDimens = MaterialTheme.chatDimens
    val chatShapes = MaterialTheme.chatShapes
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val renderCallbacks = ChatMessageRenderCallbacks(
        onSendMessage = callbacks.onSendMessage,
        onRerunMessage = callbacks.onRerunMessage,
        onSubmitApproval = callbacks.onSubmitApproval,
        onToggleRunCollapsed = callbacks.onToggleRunCollapsed,
        onToggleReasoningExpanded = callbacks.onToggleReasoningExpanded,
        onAttachmentImageTap = callbacks.onAttachmentImageTap,
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val contentWidthPx = with(density) {
            (maxWidth - chatDimens.contentPaddingHorizontal - chatDimens.contentPaddingHorizontal)
                .roundToPx()
                .coerceAtLeast(0)
        }
        val newestMessageId = state.messages.lastOrNull()?.id
        val activeStreamingGeometryBuckets = remember(
            state.isStreaming,
            newestMessageId,
            renderItems,
            contentWidthPx,
            appearance.activeFontScale,
            density.density,
            density.fontScale,
            layoutDirection,
            appearance.chatMode,
            state.collapsedRunIds,
            state.expandedReasoningMessageIds,
            state.activeApprovalRequestId,
        ) {
            if (!state.isStreaming || newestMessageId == null) {
                emptySet()
            } else {
                renderItems
                    .filter { it.containsMessageId(newestMessageId) }
                    .map {
                        it.chatGeometrySignature(
                            state = state,
                            chatMode = appearance.chatMode,
                            widthPx = contentWidthPx,
                            density = density,
                            layoutDirection = layoutDirection,
                            activeFontScale = appearance.activeFontScale,
                        ).bucket
                    }
                    .toSet()
            }
        }
        SideEffect {
            itemGeometryState.retainStreamingBuckets(activeStreamingGeometryBuckets)
        }

        CompositionLocalProvider(
            LocalChatIsPinching provides suppressPinchLayoutAnimations,
            LocalChatFontScale provides liveFontScale,
            LocalChatShouldDeferHeavyToolCards provides (
                state.isLoadingMessages ||
                    state.isLoadingOlderMessages ||
                    suppressPinchLayoutAnimations
                ),
        ) {
            val fadeTargetColor = chatFadeTargetColor(
                chatBackground = appearance.chatBackground,
                fallbackContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            )
            val topFadeLength = if (appearance.topPadding > 0.dp) appearance.topPadding + 16.dp else ChatFadeEdgeLength
            val suppressBottomFade by remember {
                derivedStateOf {
                    val lastMessage = state.messages.lastOrNull()
                    val isNearBottom = ChatViewportFollowPolicy.isNearLatest(
                        listState.toChatViewportSnapshot(isUserScrolling, renderItems.size),
                    )
                    isNearBottom && lastMessage?.role == "user" && state.isStreaming
                }
            }

            ChatFadingEdgesBox(
                listState = listState,
                targetColor = fadeTargetColor,
                modifier = Modifier.fillMaxSize(),
                topPadding = appearance.topPadding,
                topFadeLength = topFadeLength,
                bottomFadeLength = if (appearance.bottomPadding > 0.dp) appearance.bottomPadding + 48.dp else ChatFadeEdgeLength + 48.dp,
                suppressBottom = suppressBottomFade,
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = chatDimens.contentPaddingHorizontal,
                        end = chatDimens.contentPaddingHorizontal,
                        top = LettaSpacing.cardGap + appearance.topPadding,
                        bottom = LettaSpacing.cardGap + appearance.bottomPadding,
                    ),
                    reverseLayout = true,
                    modifier = Modifier.graphicsLayer { },
                ) {
                    chatMessageListItems(
                        ChatMessageListLazyContext(
                            state = state,
                            renderItems = renderItems,
                            chatMode = appearance.chatMode,
                            contentWidthPx = contentWidthPx,
                            density = density,
                            layoutDirection = layoutDirection,
                            activeFontScale = appearance.activeFontScale,
                            liveFontScale = liveFontScale,
                            newestMessageId = newestMessageId,
                            highlightedMessageId = highlightedMessageId,
                            itemGeometryState = itemGeometryState,
                            pinchFontScaleController = pinchFontScaleController,
                            scaleWindowIndexRange = scaleWindowIndexRange,
                            callbacks = renderCallbacks,
                        ),
                        chatDimens = chatDimens,
                        chatShapes = chatShapes,
                    )
                }
            }
        }

        ScrollToBottomFab(
            visible = showScrollFab,
            onClick = onScrollToBottom,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = LettaSpacing.innerPadding,
                    bottom = LettaSpacing.innerPadding + appearance.bottomPadding,
                ),
        )

        if (showFontIndicator) {
            Text(
                text = "${(liveFontScale * 100).toInt()}%",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                        RoundedCornerShape(MaterialTheme.chatShapes.bubbleRadius),
                    )
                    .padding(horizontal = LettaSpacing.innerPadding + LettaSpacing.cardGap, vertical = LettaSpacing.innerPaddingSmall),
            )
        }
    }
}
