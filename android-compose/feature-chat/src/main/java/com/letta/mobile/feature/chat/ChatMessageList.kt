package com.letta.mobile.feature.chat

import com.letta.mobile.ui.theme.LettaCodeFont

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.components.DateSeparator
import com.letta.mobile.ui.components.ScrollToBottomFab
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.theme.LocalChatFontScale
import com.letta.mobile.ui.theme.LocalChatIsPinching
import com.letta.mobile.ui.theme.chatDimens
import com.letta.mobile.ui.theme.chatShapes
import com.letta.mobile.ui.zoom.PinchScalePreviewController
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
internal fun ChatMessageList(
    state: ChatUiState,
    renderItems: List<ChatRenderItem>,
    chatMode: String,
    scrollToMessageId: String?,
    activeFontScale: Float,
    onActiveFontScaleChange: (Float) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onLoadOlderMessages: () -> Unit,
    onSendMessage: (String) -> Unit,
    onRerunMessage: (UiMessage) -> Unit,
    onSubmitApproval: (String, List<String>, Boolean, String?) -> Unit,
    onToggleRunCollapsed: (String) -> Unit,
    onToggleReasoningExpanded: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val chatDimens = MaterialTheme.chatDimens
    val chatShapes = MaterialTheme.chatShapes
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val itemGeometryState = remember { ChatMessageGeometryState() }
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var hasScrolledToTarget by remember { mutableStateOf(false) }
    var showFontIndicator by remember { mutableStateOf(false) }
    var pinchTick by remember { mutableStateOf(0L) }
    var pinchAnimationSuppressionTick by remember { mutableStateOf(0L) }
    var suppressPinchLayoutAnimations by remember { mutableStateOf(false) }
    val pinchFontScaleController = remember {
        PinchScalePreviewController(minScale = 0.7f, maxScale = 1.6f, step = 0.02f)
    }
    // letta-mobile-6261e: drive real text re-layout during pinch instead of a
    // graphicsLayer bitmap scale. During a gesture the controller's
    // effectiveScale tracks the pointer frame-by-frame; we re-provide
    // LocalChatFontScale below so every Text/markdown block reads the live
    // value and Compose re-measures + reflows per frame. When not pinching,
    // effectiveScale equals the committed activeFontScale, so this path is
    // safe steady-state too.
    val liveFontScale = if (pinchFontScaleController.isPinching) {
        pinchFontScaleController.effectiveScale
    } else {
        activeFontScale
    }
    SideEffect {
        pinchFontScaleController.syncCommittedScale(activeFontScale)
    }

    // letta-mobile-8u662: viewport-bounded scale window for live pinch
    // reflow. During a pinch we only need to drive real text re-layout for
    // items the user can see — items composed off-screen by the LazyColumn
    // for prefetch keep their cached committed-scale geometry until the
    // gesture commits or they enter the window via scroll.
    //
    // The window is the currently-visible LazyColumn item range plus a
    // margin proportional to the gesture's scale ratio: less margin when
    // scaling DOWN (fewer items fit on screen so cost is bounded anyway),
    // more margin when scaling UP (items get larger and the user might
    // scroll mid-gesture, so prefetched items at the edges should already
    // be at the live scale).
    //
    // Outside this window items see activeFontScale (the committed value)
    // and reuse their cached geometry; inside they see liveFontScale and
    // reflow every pointer frame. When the gesture ends, every item
    // converges on the new committed scale and the cache re-seeds per
    // letta-mobile-8vivd.
    //
    // Date separators, loading indicators, and other cheap items are
    // outside renderItems and just see liveFontScale via the existing
    // CompositionLocalProvider — they're one-line labels, free to reflow.
    val scaleWindowIndexRange: IntRange = if (pinchFontScaleController.isPinching) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) {
            IntRange.EMPTY
        } else {
            val visibleCount = visible.size
            val scaleRatio = if (activeFontScale > 0f) liveFontScale / activeFontScale else 1f
            val marginItems = (visibleCount * scaleRatio).toInt().coerceAtLeast(2)
            val firstIdx = visible.first().index
            val lastIdx = visible.last().index
            (firstIdx - marginItems).coerceAtLeast(0)..(lastIdx + marginItems)
        }
    } else {
        IntRange.EMPTY
    }

    LaunchedEffect(pinchTick) {
        if (pinchTick > 0) {
            showFontIndicator = true
            delay(1000)
            showFontIndicator = false
        }
    }

    LaunchedEffect(pinchAnimationSuppressionTick) {
        if (pinchAnimationSuppressionTick > 0) {
            delay(ChatMotion.ContentSizeMillis.toLong())
            if (!pinchFontScaleController.isPinching) {
                suppressPinchLayoutAnimations = false
            }
        }
    }

    val autoScrollSignature by rememberUpdatedState(newestMessageAutoScrollSignature(state.messages))

    val isNearBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex <= 1 && listState.firstVisibleItemScrollOffset < 90
        }
    }

    val showScrollFab by remember {
        derivedStateOf {
            val firstVisible = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
            firstVisible > 3
        }
    }

    val shouldLoadOlderMessages by remember {
        derivedStateOf {
            if (!state.hasMoreOlderMessages || state.isLoadingOlderMessages || state.messages.isEmpty()) {
                return@derivedStateOf false
            }

            val lastVisible = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 0 && lastVisible >= totalItems - 3
        }
    }

    // Keep the bottom anchored while new messages arrive or the newest assistant
    // bubble grows during streaming. With reverseLayout=true, item 0 is the
    // newest edge (the typing slot), so scrolling to 0 means "bottom".
    LaunchedEffect(Unit) {
        snapshotFlow { autoScrollSignature }
            .distinctUntilChanged()
            .collect { signature ->
                if (signature != null && isNearBottom && scrollToMessageId == null) {
                    listState.animateScrollToItem(0)
                }
            }
    }

    LaunchedEffect(listState, state.hasMoreOlderMessages, state.isLoadingOlderMessages, state.messages.size) {
        snapshotFlow { shouldLoadOlderMessages }
            .distinctUntilChanged()
            .collect { shouldLoad ->
                if (shouldLoad) {
                    onLoadOlderMessages()
                }
            }
    }

    // Scroll to a specific message when navigating from search results
    LaunchedEffect(scrollToMessageId, renderItems.size) {
        if (scrollToMessageId == null || hasScrolledToTarget || renderItems.isEmpty()) return@LaunchedEffect
        val targetIdx = renderItems.indexOfFirst { it.containsMessageId(scrollToMessageId) }
        if (targetIdx >= 0) {
            listState.scrollToItem(
                calculateLazyIndexForRenderItem(
                    targetRenderIndex = targetIdx,
                    renderItems = renderItems,
                ),
            )
            highlightedMessageId = scrollToMessageId
            hasScrolledToTarget = true
            delay(2000)
            highlightedMessageId = null
        }
    }

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
                                suppressPinchLayoutAnimations = true
                                pinchAnimationSuppressionTick = 0L
                                pinchFontScaleController.begin(activeFontScale)
                                pinchTick = System.nanoTime()
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
                        onActiveFontScaleChange(snapped)
                        onFontScaleChange(snapped)
                        pinchTick = System.nanoTime()
                        pinchAnimationSuppressionTick = pinchTick
                    } else {
                        pinchFontScaleController.cancel()
                        suppressPinchLayoutAnimations = false
                    }
                }
            },
    ) {
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
            activeFontScale,
            density.density,
            density.fontScale,
            layoutDirection,
            chatMode,
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
                            chatMode = chatMode,
                            widthPx = contentWidthPx,
                            density = density,
                            layoutDirection = layoutDirection,
                            activeFontScale = activeFontScale,
                        ).bucket
                    }
                    .toSet()
            }
        }
        SideEffect {
            itemGeometryState.retainStreamingBuckets(activeStreamingGeometryBuckets)
        }

        // letta-mobile-5e0f.r2: provide LocalChatIsPinching to
        // the entire chat content tree so animateContentSize
        // sites can suppress themselves during the gesture.
        //
        // letta-mobile-6261e: also override LocalChatFontScale with the
        // controller's live effectiveScale so text re-measures + reflows
        // every pointer frame during a pinch. The previous implementation
        // applied a `graphicsLayer { scaleX = visualFontScale ... }` to
        // the LazyColumn which bitmap-scaled the rendered output without
        // re-laying out. That looked OK but felt static — text didn't
        // reflow until the gesture committed. Now reflow is continuous.
        // ChatTypography is already memoized on fontScale in LettaChatTheme,
        // so feeding distinct values per frame allocates one ChatTypography
        // per unique scale and downstream readers recompose only when the
        // scale actually changes.
        CompositionLocalProvider(
            LocalChatIsPinching provides suppressPinchLayoutAnimations,
            LocalChatFontScale provides liveFontScale,
        ) {
            LazyColumn(
                state = listState,
                // Use the chat theme's compact gutter so assistant prose,
                // tool output, and run blocks get the widest useful line
                // length without touching the screen edge.
                contentPadding = PaddingValues(
                    horizontal = chatDimens.contentPaddingHorizontal,
                    vertical = LettaSpacing.cardGap,
                ),
                reverseLayout = true,
                // letta-mobile-erhjl: keep an identity graphicsLayer so the
                // LazyColumn rasterizes its visible items into an offscreen
                // GPU layer once and moves the LAYER during scroll instead
                // of re-rasterizing every frame. The original modifier here
                // was doing this incidentally via scaleX/scaleY = visualScale;
                // PR #280 removed it to enable live LocalChatFontScale reflow,
                // but the rasterization barrier was load-bearing for scroll
                // performance. The actual scale now comes from the surrounding
                // CompositionLocalProvider(LocalChatFontScale provides ...),
                // which drives real text re-layout; this graphicsLayer block
                // is intentionally empty (identity transform) and exists only
                // for the offscreen-layer optimization.
                modifier = Modifier.graphicsLayer { },
            ) {
                // letta-mobile-vcky.b2: the thinking glow moved out of the
                // list and into a Box overlay above the ChatComposer (see
                // ChatScreen). It now appears to emanate from behind the
                // top edge of the message field rather than sitting as a
                // discrete strip in the list.

                renderItems.forEachIndexed { index, renderItem ->
                    val prevDate = renderItems.getOrNull(index + 1)?.boundaryTimestamp?.take(10)
                    val currentDate = renderItem.boundaryTimestamp.take(10)
                    val showDate = prevDate != null && prevDate != currentDate

                    item(key = renderItem.key, contentType = when (renderItem) {
                        is ChatRenderItem.Single -> "single"
                        is ChatRenderItem.RunBlock -> "runblock"
                    }) {
                        val geometrySignature = renderItem.chatGeometrySignature(
                            state = state,
                            chatMode = chatMode,
                            widthPx = contentWidthPx,
                            density = density,
                            layoutDirection = layoutDirection,
                            activeFontScale = activeFontScale,
                        )
                        val isStreamingRenderItem = state.isStreaming &&
                            newestMessageId != null &&
                            renderItem.containsMessageId(newestMessageId)
                        // letta-mobile-8u662: during a pinch, items outside
                        // the viewport-bounded scale window keep the committed
                        // activeFontScale (reusing cached geometry) instead of
                        // tracking the live gesture. The CompositionLocalProvider
                        // up the tree provides liveFontScale globally; here we
                        // override BACK to activeFontScale for off-window items.
                        // The renderItems index is approximate (date separators
                        // are injected into the LazyColumn between them, so
                        // visible indices don't map 1:1) — the margin already
                        // absorbs this drift.
                        val itemSeesLiveScale = !pinchFontScaleController.isPinching ||
                            scaleWindowIndexRange.isEmpty() ||
                            index in scaleWindowIndexRange
                        val perItemFontScale = if (itemSeesLiveScale) liveFontScale else activeFontScale
                        CompositionLocalProvider(
                            LocalChatFontScale provides perItemFontScale,
                        ) {
                        // letta-mobile-lbur follow-up: log render item keys for dedup analysis
                        MeasuredChatRenderItem(
                            signature = geometrySignature,
                            geometryState = itemGeometryState,
                            isStreaming = isStreamingRenderItem,
                        ) {
                            when (renderItem) {
                                is ChatRenderItem.Single -> {
                                // letta-mobile-m772.4 follow-up: reasoning bubbles that
                                // land as Single (because their run had only one message,
                                // or because the message predates runId tracking) still
                                // need the collapse affordance — otherwise the body is
                                // always shown with no toggle. Thread the same callbacks
                                // RunBlock uses so behaviour is consistent across modes
                                // and group sizes.
                                val msg = renderItem.message
                                // letta-mobile-d2z6 (real fix): when w9l3 marked this
                                // Single with a stableRunKey it means "this message's
                                // run has exactly one message *right now* but a sibling
                                // is likely about to land, promoting it to a RunBlock
                                // with the same LazyColumn key". Routing both states
                                // through the same composable (RunBlock) keeps the
                                // slot's composable subtree identical across the
                                // transition — Compose just sees the messages list
                                // grow from 1 to N inside the existing RunBlock. The
                                // RunBlock's messages.size == 1 short-circuit renders
                                // exactly what RenderChatMessage would render, so
                                // there's no visible change for true singletons.
                                val stableKey = renderItem.stableRunKey
                                if (stableKey != null) {
                                    val runId = stableKey.removePrefix("run-")
                                    RunBlock(
                                        messages = listOf(msg),
                                        collapsed = runId in state.collapsedRunIds,
                                        onToggleCollapsed = { onToggleRunCollapsed(runId) },
                                        modifier = Modifier.padding(top = chatDimens.ungroupedMessageSpacing),
                                        isStreaming = state.isStreaming,
                                        activeApprovalRequestId = state.activeApprovalRequestId,
                                        onApprovalDecision = onSubmitApproval,
                                    ) { message, position, rowModifier ->
                                        RenderChatMessage(
                                            message = message,
                                            position = position,
                                            state = state,
                                            chatMode = chatMode,
                                            highlightedMessageId = highlightedMessageId,
                                            onSendMessage = onSendMessage,
                                            onRerunMessage = onRerunMessage,
                                            onSubmitApproval = onSubmitApproval,
                                            reasoningCollapsed = message.id !in state.expandedReasoningMessageIds,
                                            onToggleReasoning = { onToggleReasoningExpanded(message.id) },
                                            modifier = rowModifier,
                                        )
                                    }
                                } else {
                                    RenderChatMessage(
                                        message = msg,
                                        position = renderItem.groupPosition,
                                        state = state,
                                        chatMode = chatMode,
                                        highlightedMessageId = highlightedMessageId,
                                        onSendMessage = onSendMessage,
                                        onRerunMessage = onRerunMessage,
                                        onSubmitApproval = onSubmitApproval,
                                        reasoningCollapsed = msg.id !in state.expandedReasoningMessageIds,
                                        onToggleReasoning = { onToggleReasoningExpanded(msg.id) },
                                    )
                                }
                            }

                                is ChatRenderItem.RunBlock -> {
                                val isHighlighted = renderItem.containsMessageId(highlightedMessageId.orEmpty())
                                val highlightModifier = if (isHighlighted) {
                                    Modifier.background(
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                        RoundedCornerShape(chatShapes.bubbleRadius),
                                    )
                                } else {
                                    Modifier
                                }
                                RunBlock(
                                    messages = renderItem.messages.map { it.first },
                                    collapsed = renderItem.runId in state.collapsedRunIds,
                                    onToggleCollapsed = { onToggleRunCollapsed(renderItem.runId) },
                                    modifier = highlightModifier.padding(top = chatDimens.ungroupedMessageSpacing),
                                    isStreaming = state.isStreaming,
                                    activeApprovalRequestId = state.activeApprovalRequestId,
                                    onApprovalDecision = onSubmitApproval,
                                ) { message, position, rowModifier ->
                                    RenderChatMessage(
                                        message = message,
                                        position = position,
                                        state = state,
                                        chatMode = chatMode,
                                        highlightedMessageId = highlightedMessageId,
                                        onSendMessage = onSendMessage,
                                        onRerunMessage = onRerunMessage,
                                        onSubmitApproval = onSubmitApproval,
                                        reasoningCollapsed = message.id !in state.expandedReasoningMessageIds,
                                        onToggleReasoning = { onToggleReasoningExpanded(message.id) },
                                        modifier = rowModifier,
                                    )
                                }
                            }
                        }
                    }
                        } // letta-mobile-8u662: end CompositionLocalProvider(LocalChatFontScale)
                    }

                    if (showDate) {
                        // Tie the separator key to the boundary message id so
                        // the same date can legitimately appear multiple times
                        // (e.g. after older-page merges) without colliding.
                        item(key = "date-${renderItem.key}", contentType = "date") {
                            val date = try {
                                LocalDate.parse(currentDate)
                            } catch (_: Exception) {
                                null
                            }
                            if (date != null) {
                                DateSeparator(date = date)
                            }
                        }
                    }
                }

                if (state.isLoadingOlderMessages) {
                    item(key = "older-loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = LettaSpacing.innerPaddingSmall),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        } // letta-mobile-5e0f.r2: end CompositionLocalProvider(LocalChatIsPinching)

        ScrollToBottomFab(
            visible = showScrollFab,
            onClick = { scope.launch { listState.animateScrollToItem(0) } },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(LettaSpacing.innerPadding),
        )

        if (showFontIndicator) {
            // letta-mobile-6261e: indicator now tracks the live effective
            // scale during a gesture (so the user sees the real % they're
            // pinching toward) and falls back to the committed scale when
            // not pinching. Previously it only updated on commit because
            // the visual scaling was bitmap-based and indicator could lie.
            Text(
                text = "${(liveFontScale * 100).toInt()}%",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                        RoundedCornerShape(chatShapes.bubbleRadius),
                    )
                    .padding(horizontal = LettaSpacing.innerPadding + LettaSpacing.cardGap, vertical = LettaSpacing.innerPaddingSmall),
            )
        }
    }
}

@Composable
private fun MeasuredChatRenderItem(
    signature: ChatRenderItemGeometrySignature,
    geometryState: ChatMessageGeometryState,
    isStreaming: Boolean,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val isPinching = LocalChatIsPinching.current
    // letta-mobile-8vivd: while the user is actively pinching, suppress the
    // height-floor from the geometry cache. The floor exists to keep streaming
    // items stable across re-measures (so a bubble that just landed at H px
    // can't suddenly report H-30 mid-stream and jitter), but during pinch the
    // user explicitly asked everything to be smaller — applying the floor at
    // the previous-scale value leaves a phantom gap at the bottom of the
    // LazyColumn because items can't shrink below the cached floor.
    //
    // Skipping the floor during pinch lets items take their actual reflowed
    // size each frame. The cache itself is unchanged (signature already keys
    // on chatFontScaleBucket, so the committed scale's floor is still valid
    // and ready to apply the moment the gesture ends).
    val heightFloorPx = if (isPinching) 0 else geometryState.heightFloorFor(signature, isStreaming)
    val minHeightModifier = if (heightFloorPx > 0) {
        Modifier.heightIn(min = with(density) { heightFloorPx.toDp() })
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(minHeightModifier)
            .onSizeChanged { size ->
                // letta-mobile-8vivd: don't record measurements taken during a
                // pinch into the cache — those heights are the live (mid-
                // gesture) values which would corrupt the committed-scale
                // cache entries. Once the gesture ends and content re-measures
                // at the committed scale, the next onSizeChanged seeds the
                // cache cleanly.
                if (!isPinching) {
                    geometryState.recordMeasuredHeight(
                        signature = signature,
                        heightPx = size.height,
                        isStreaming = isStreaming,
                    )
                }
            },
    ) {
        content()
    }
}

internal data class ChatAutoScrollSignature(
    val messageId: String,
    val role: String,
    val contentLength: Int,
    val contentHash: Int,
    val latencyMs: Long?,
    val toolCallsHash: Int,
    val generatedUiHash: Int,
    val approvalHash: Int,
    val attachmentCount: Int,
)

internal fun newestMessageAutoScrollSignature(messages: List<UiMessage>): ChatAutoScrollSignature? {
    val newest = messages.lastOrNull() ?: return null
    return ChatAutoScrollSignature(
        messageId = newest.id,
        role = newest.role,
        contentLength = newest.content.length,
        contentHash = newest.content.hashCode(),
        latencyMs = newest.latencyMs,
        toolCallsHash = newest.toolCalls?.hashCode() ?: 0,
        generatedUiHash = newest.generatedUi?.hashCode() ?: 0,
        approvalHash = 31 * (newest.approvalRequest?.hashCode() ?: 0) +
            (newest.approvalResponse?.hashCode() ?: 0),
        attachmentCount = newest.attachments.size,
    )
}

internal fun calculateLazyIndexForRenderItem(
    targetRenderIndex: Int,
    renderItems: List<ChatRenderItem>,
): Int {
    var lazyIndex = 1
    for (j in 0 until targetRenderIndex) {
        lazyIndex++ // message item
        val prevDate = renderItems.getOrNull(j + 1)?.boundaryTimestamp?.take(10)
        val curDate = renderItems[j].boundaryTimestamp.take(10)
        if (prevDate != null && prevDate != curDate) lazyIndex++
    }
    return lazyIndex
}

@Composable
private fun RenderChatMessage(
    message: UiMessage,
    position: GroupPosition,
    state: ChatUiState,
    chatMode: String,
    highlightedMessageId: String?,
    onSendMessage: (String) -> Unit,
    onRerunMessage: (UiMessage) -> Unit,
    onSubmitApproval: (String, List<String>, Boolean, String?) -> Unit,
    reasoningCollapsed: Boolean = false,
    onToggleReasoning: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // reverseLayout = true: top = space below (toward newer),
    // bottom = space above (toward older)
    val spacingBelow = when {
        position == GroupPosition.Middle || position == GroupPosition.Last -> MaterialTheme.chatDimens.groupedMessageSpacing
        else -> MaterialTheme.chatDimens.ungroupedMessageSpacing
    }
    val spacingAbove = if (message.isReasoning) LettaSpacing.innerPaddingSmall else LettaSpacing.none
    val isHighlighted = message.id == highlightedMessageId
    val highlightModifier = if (isHighlighted) {
        Modifier.background(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            RoundedCornerShape(MaterialTheme.chatShapes.bubbleRadius),
        )
    } else {
        Modifier
    }
    if (chatMode == "debug") {
        DebugMessageCard(
            message = message,
            modifier = modifier.then(highlightModifier).padding(top = spacingBelow, bottom = spacingAbove),
        )
    } else {
        ChatMessageItem(
            message = message,
            groupPosition = position,
            isStreaming = state.isStreaming && message.id == state.messages.lastOrNull()?.id,
            reasoningCollapsed = reasoningCollapsed,
            onToggleReasoning = onToggleReasoning,
            onGeneratedUiMessage = onSendMessage,
            onRerunMessage = onRerunMessage,
            rerunEnabled = !state.isStreaming,
            onApprovalDecision = { requestId, toolCallIds, approve, reason ->
                onSubmitApproval(requestId, toolCallIds, approve, reason)
            },
            approvalInFlight = state.activeApprovalRequestId == message.approvalRequest?.requestId,
            modifier = modifier.then(highlightModifier).padding(top = spacingBelow, bottom = spacingAbove),
        )
    }
}

@Composable
private fun DebugMessageCard(
    message: UiMessage,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(LettaSpacing.cardGap)) {
            Text(
                text = "${message.role} | ${message.id}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(LettaSpacing.cardGroupItemGap + LettaSpacing.cardGroupItemGap))
            Text(
                text = buildString {
                    append("content: ${message.content.take(200)}")
                    if (message.content.length > 200) append("...")
                    if (message.isReasoning) append("\nisReasoning: true")
                    message.toolCalls?.forEach { tc ->
                        append("\ntool: ${tc.name}(${tc.arguments.take(100)})")
                        tc.result?.let { append("\nresult: ${it.take(100)}") }
                    }
                },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = LettaCodeFont,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
