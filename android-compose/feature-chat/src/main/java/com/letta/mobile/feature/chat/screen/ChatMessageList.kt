package com.letta.mobile.feature.chat.screen

import android.view.Choreographer
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
import androidx.compose.runtime.DisposableEffect
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
import com.letta.mobile.data.model.UiImageAttachment
import kotlinx.collections.immutable.toImmutableList
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.components.DateSeparator
import com.letta.mobile.ui.components.ScrollToBottomFab
import com.letta.mobile.ui.theme.ChatBackground
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.theme.LocalChatFontScale
import com.letta.mobile.ui.theme.LocalChatIsPinching
import com.letta.mobile.ui.theme.chatDimens
import com.letta.mobile.ui.theme.chatShapes
import com.letta.mobile.ui.zoom.PinchScalePreviewController
import com.letta.mobile.util.Telemetry
import java.time.LocalDate
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.letta.mobile.feature.chat.coordination.ChatRenderItem
import com.letta.mobile.feature.chat.render.ChatMessageGeometryState
import com.letta.mobile.feature.chat.render.ChatRenderItemGeometrySignature
import com.letta.mobile.feature.chat.render.ChatUiState
import com.letta.mobile.feature.chat.render.LocalToolCardBodyParentVisible
import com.letta.mobile.feature.chat.render.chatGeometrySignature

internal fun chatRenderItemSeesLiveScale(
    isPinching: Boolean,
    scaleWindowIndexRange: IntRange,
    itemIndex: Int,
): Boolean = !isPinching || scaleWindowIndexRange.isEmpty() || itemIndex in scaleWindowIndexRange

private data class ChatPinchVisibleContentSummary(
    val userMessages: Int,
    val assistantMessages: Int,
    val toolCards: Int,
    val runBlocks: Int,
)

private data class ChatLoadPressureSummary(
    val messageCount: Int,
    val renderItemCount: Int,
    val isStreaming: Boolean,
    val isLoadingMessages: Boolean,
    val isLoadingOlderMessages: Boolean,
    val toolCardCount: Int,
) {
    val isHydrating: Boolean = isLoadingMessages
    val isReconciling: Boolean = false
}

private fun Collection<ChatRenderItem>.pinchVisibleContentSummary(): ChatPinchVisibleContentSummary {
    var userMessages = 0
    var assistantMessages = 0
    var toolCards = 0
    var runBlocks = 0

    fun countMessage(message: UiMessage) {
        when {
            message.role == "user" -> userMessages++
            message.role == "assistant" -> assistantMessages++
        }
        if (message.role == "tool" || !message.toolCalls.isNullOrEmpty() || message.generatedUi != null) {
            toolCards++
        }
    }

    for (item in this) {
        when (item) {
            is ChatRenderItem.Single -> countMessage(item.message)
            is ChatRenderItem.RunBlock -> {
                runBlocks++
                item.messages.forEach { (message, _) -> countMessage(message) }
            }
        }
    }

    return ChatPinchVisibleContentSummary(
        userMessages = userMessages,
        assistantMessages = assistantMessages,
        toolCards = toolCards,
        runBlocks = runBlocks,
    )
}

private class ChatPinchFrameBudgetSampler {
    private val frameDurationsMs = ArrayList<Long>(240)
    private var choreographer: Choreographer? = null
    private var startedAtMs = 0L
    private var lastFrameTimeNanos = 0L
    private var visibleItems = 0
    private var totalItems = 0
    private var visibleUserMessages = 0
    private var visibleAssistantMessages = 0
    private var visibleToolCards = 0
    private var visibleRunBlocks = 0
    private var loadPressure = ChatLoadPressureSummary(
        messageCount = 0,
        renderItemCount = 0,
        isStreaming = false,
        isLoadingMessages = false,
        isLoadingOlderMessages = false,
        toolCardCount = 0,
    )
    private var committedScale = 1f
    private var running = false

    private val callback: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val previous = lastFrameTimeNanos
            lastFrameTimeNanos = frameTimeNanos
            if (previous != 0L) {
                frameDurationsMs += ((frameTimeNanos - previous) / 1_000_000L).coerceAtLeast(0L)
            }
            choreographer?.postFrameCallback(this)
        }
    }

    fun start(
        visibleItems: Int,
        totalItems: Int,
        visibleUserMessages: Int,
        visibleAssistantMessages: Int,
        visibleToolCards: Int,
        visibleRunBlocks: Int,
        loadPressure: ChatLoadPressureSummary,
        committedScale: Float,
    ) {
        cancel()
        this.visibleItems = visibleItems
        this.totalItems = totalItems
        this.visibleUserMessages = visibleUserMessages
        this.visibleAssistantMessages = visibleAssistantMessages
        this.visibleToolCards = visibleToolCards
        this.visibleRunBlocks = visibleRunBlocks
        this.loadPressure = loadPressure
        this.committedScale = committedScale
        frameDurationsMs.clear()
        startedAtMs = System.currentTimeMillis()
        lastFrameTimeNanos = 0L
        running = true
        choreographer = Choreographer.getInstance().also { it.postFrameCallback(callback) }
        Telemetry.event(
            "ChatPinch",
            "frameBudget.started",
            "visibleItems" to visibleItems,
            "totalItems" to totalItems,
            "visibleUserMessages" to visibleUserMessages,
            "visibleAssistantMessages" to visibleAssistantMessages,
            "visibleToolCards" to visibleToolCards,
            "visibleRunBlocks" to visibleRunBlocks,
            "messageCount" to loadPressure.messageCount,
            "renderItemCount" to loadPressure.renderItemCount,
            "isStreaming" to loadPressure.isStreaming,
            "isLoadingMessages" to loadPressure.isLoadingMessages,
            "isLoadingOlderMessages" to loadPressure.isLoadingOlderMessages,
            "isHydrating" to loadPressure.isHydrating,
            "isReconciling" to loadPressure.isReconciling,
            "toolCardCount" to loadPressure.toolCardCount,
            "committedScale" to committedScale,
        )
    }

    fun stop(committedScale: Float, targetScale: Float) {
        if (!running) return
        val elapsedMs = System.currentTimeMillis() - startedAtMs
        val frames = frameDurationsMs.toList()
        cancel()
        if (frames.isEmpty()) {
            Telemetry.event(
                "ChatPinch",
                "frameBudget.finished",
                "frames" to 0,
                "elapsedMs" to elapsedMs,
                "committedScale" to committedScale,
                "targetScale" to targetScale,
                "visibleItems" to visibleItems,
                "totalItems" to totalItems,
                "visibleUserMessages" to visibleUserMessages,
                "visibleAssistantMessages" to visibleAssistantMessages,
                "visibleToolCards" to visibleToolCards,
                "visibleRunBlocks" to visibleRunBlocks,
                "messageCount" to loadPressure.messageCount,
                "renderItemCount" to loadPressure.renderItemCount,
                "isStreaming" to loadPressure.isStreaming,
                "isLoadingMessages" to loadPressure.isLoadingMessages,
                "isLoadingOlderMessages" to loadPressure.isLoadingOlderMessages,
                "isHydrating" to loadPressure.isHydrating,
                "isReconciling" to loadPressure.isReconciling,
                "toolCardCount" to loadPressure.toolCardCount,
            )
            return
        }
        val sorted = frames.sorted()
        val frameBudgetMs = 16L
        val jankFrames = frames.count { it > frameBudgetMs }
        val maxMs = frames.maxOrNull() ?: 0L
        val avgMs = frames.average()
        val p95Index = ((sorted.size - 1) * 95 / 100).coerceIn(0, sorted.lastIndex)
        Telemetry.event(
            "ChatPinch",
            "frameBudget.finished",
            "frames" to frames.size,
            "jankFrames" to jankFrames,
            "jankPercent" to ((jankFrames * 100.0) / frames.size),
            "avgMs" to avgMs,
            "p95Ms" to sorted[p95Index],
            "maxMs" to maxMs,
            "overBudgetTotalMs" to frames.sumOf { (it - frameBudgetMs).coerceAtLeast(0L) },
            "elapsedMs" to elapsedMs,
            "committedScale" to committedScale,
            "targetScale" to targetScale,
            "visibleItems" to visibleItems,
            "totalItems" to totalItems,
            "visibleUserMessages" to visibleUserMessages,
            "visibleAssistantMessages" to visibleAssistantMessages,
            "visibleToolCards" to visibleToolCards,
            "visibleRunBlocks" to visibleRunBlocks,
            "messageCount" to loadPressure.messageCount,
            "renderItemCount" to loadPressure.renderItemCount,
            "isStreaming" to loadPressure.isStreaming,
            "isLoadingMessages" to loadPressure.isLoadingMessages,
            "isLoadingOlderMessages" to loadPressure.isLoadingOlderMessages,
            "isHydrating" to loadPressure.isHydrating,
            "isReconciling" to loadPressure.isReconciling,
            "toolCardCount" to loadPressure.toolCardCount,
        )
    }

    fun cancel() {
        if (running) {
            choreographer?.removeFrameCallback(callback)
        }
        running = false
        choreographer = null
        lastFrameTimeNanos = 0L
    }
}

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
    chatBackground: ChatBackground = ChatBackground.Default,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val chatDimens = MaterialTheme.chatDimens
    val chatShapes = MaterialTheme.chatShapes
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // perf/frame-budget-audit: `renderItems` gets a fresh identity on every
    // streamed token, so a `remember(renderItems) { associateBy { it.key } }`
    // here rebuilt an O(conversation) map PER TOKEN purely to serve the
    // pinch-begin handler below (its only consumer) — the same rmzmo/gsvgt
    // O(history)-per-token smell. Keep the latest renderItems available via
    // rememberUpdatedState and build the lookup lazily, once, when a pinch
    // actually begins (a rare gesture).
    val currentRenderItems by rememberUpdatedState(renderItems)
    val itemGeometryState = remember { ChatMessageGeometryState() }
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var hasScrolledToTarget by remember { mutableStateOf(false) }
    var showFontIndicator by remember { mutableStateOf(false) }
    // letta-mobile-1k3ge restore: fullscreen image viewer state. Hoisted here
    // so any message's tapped attachment opens the viewer overlay below.
    var imageViewerState by remember {
        mutableStateOf<Pair<kotlinx.collections.immutable.ImmutableList<UiImageAttachment>, Int>?>(null)
    }
    val onAttachmentImageTap: (List<UiImageAttachment>, Int) -> Unit = { attachments, index ->
        imageViewerState = attachments.toImmutableList() to index
    }
    var pinchTick by remember { mutableStateOf(0L) }
    var pinchAnimationSuppressionTick by remember { mutableStateOf(0L) }
    var suppressPinchLayoutAnimations by remember { mutableStateOf(false) }
    val pinchFontScaleController = remember {
        PinchScalePreviewController(minScale = 0.7f, maxScale = 1.6f, step = 0.02f)
    }
    val pinchFrameBudgetSampler = remember { ChatPinchFrameBudgetSampler() }
    DisposableEffect(Unit) {
        onDispose { pinchFrameBudgetSampler.cancel() }
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
    val isStreamingForAutoScroll by rememberUpdatedState(state.isStreaming)
    // letta-mobile-gsvgt (F1): keep this per-tick summary O(1). The previous
    // implementation keyed the remember on state.messages AND renderItems —
    // both of which get a fresh identity on every streamed token — and its
    // body called renderItems.pinchVisibleContentSummary(), an O(total
    // conversation) walk over every render item and every message inside
    // every RunBlock. That ran PER TOKEN during streaming purely to keep a
    // telemetry value warm, which is exactly the rmzmo O(history)-per-token
    // class. The only consumer is the pinch-begin handler below, so the
    // expensive toolCardCount is now computed lazily there (over the already
    // O(visible) render-item set) instead of eagerly every tick. Keeping
    // toolCardCount = 0 here makes the steady-state summary allocation-light
    // and history-independent.
    val loadPressureSummary = remember(
        state.messages.size,
        state.isStreaming,
        state.isLoadingMessages,
        state.isLoadingOlderMessages,
        renderItems.size,
    ) {
        ChatLoadPressureSummary(
            messageCount = state.messages.size,
            renderItemCount = renderItems.size,
            isStreaming = state.isStreaming,
            isLoadingMessages = state.isLoadingMessages,
            isLoadingOlderMessages = state.isLoadingOlderMessages,
            toolCardCount = 0,
        )
    }
    val currentLoadPressureSummary by rememberUpdatedState(loadPressureSummary)

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

    var lastStreamingSnapMs by remember { mutableStateOf(0L) }
    // letta-mobile-4bgm3: track the newest message id we've already handled so
    // we can detect when a brand-new user message lands (i.e. the user just
    // hit send). Seeded null so the very first signature emission can be
    // classified, but the force-scroll only fires for role == "user".
    var lastHandledNewestMessageId by remember { mutableStateOf<String?>(null) }

    // Keep the bottom anchored while new messages arrive or the newest assistant
    // bubble grows during streaming. With reverseLayout=true, item 0 is the
    // newest edge (the typing slot), so scrolling to 0 means "bottom".
    LaunchedEffect(Unit) {
        snapshotFlow { autoScrollSignature }
            .distinctUntilChanged()
            .collect { signature ->
                if (signature == null || scrollToMessageId != null) {
                    lastHandledNewestMessageId = signature?.messageId
                    return@collect
                }

                // letta-mobile-4bgm3: when the user SENDS a message, the
                // optimistic user bubble becomes the newest item. Force the
                // viewport to that bubble regardless of current scroll
                // position — distinct from the streaming auto-scroll throttle,
                // which only snaps when already pinned near the bottom. Without
                // this, sending from a scrolled-up position in a long
                // conversation leaves the new prompt off the top edge.
                val previousNewestId = lastHandledNewestMessageId
                lastHandledNewestMessageId = signature.messageId
                if (shouldForceScrollOnUserSend(
                        signature = signature,
                        previousNewestMessageId = previousNewestId,
                    )
                ) {
                    // letta-mobile-58qlr.1: land the just-sent prompt CLEAR of
                    // the bottom fading edge. With reverseLayout=true item 0 is
                    // the newest item at the visual bottom; animateScrollToItem
                    // would pin it flush to the bottom edge, where the
                    // ChatFadingEdges overlay (bottom ~fade length) would dim
                    // the user's own fresh prompt. A negative scrollOffset
                    // shifts item 0 UP by the fade length so the whole bubble
                    // sits above the fade zone and stays fully visible.
                    val sendScrollOffset = with(density) { -ChatFadeEdgeLength.roundToPx() }
                    listState.animateScrollToItem(0, sendScrollOffset)
                    return@collect
                }

                if (isNearBottom) {
                    val nowMs = System.currentTimeMillis()
                    when (autoScrollAction(
                        signature = signature,
                        isStreaming = isStreamingForAutoScroll,
                        firstVisibleItemIndex = listState.firstVisibleItemIndex,
                        firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                        lastStreamingSnapMs = lastStreamingSnapMs,
                        nowMs = nowMs,
                    )) {
                        ChatAutoScrollAction.Animate -> listState.animateScrollToItem(0)
                        ChatAutoScrollAction.Snap -> {
                            lastStreamingSnapMs = nowMs
                            listState.scrollToItem(0)
                        }
                        ChatAutoScrollAction.Skip -> Unit
                    }
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
                                // perf/frame-budget-audit: build the key->item
                                // lookup lazily here (pinch-begin only) over the
                                // current render items, instead of eagerly per
                                // token in composition. Only the visible item
                                // keys are looked up, so a single associateBy at
                                // gesture start is cheap and history-bounded.
                                val renderItemsByKey = currentRenderItems.associateBy { it.key }
                                val visibleRenderItems = listState.layoutInfo.visibleItemsInfo.mapNotNull { itemInfo ->
                                    renderItemsByKey[itemInfo.key]
                                }
                                val visibleContent = visibleRenderItems.pinchVisibleContentSummary()
                                // letta-mobile-gsvgt (F1): compute the
                                // full-list tool-card count ONCE here, at
                                // pinch-begin, rather than eagerly every
                                // streamed token. This is a rare, one-shot
                                // gesture event so the O(total render items)
                                // walk is acceptable; the steady-state
                                // per-tick path stays O(1).
                                val loadPressureForSample = currentLoadPressureSummary.copy(
                                    toolCardCount = renderItems.pinchVisibleContentSummary().toolCards,
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
                        pinchFrameBudgetSampler.stop(committedScale = activeFontScale, targetScale = snapped)
                        pinchTick = System.nanoTime()
                        pinchAnimationSuppressionTick = pinchTick
                    } else {
                        pinchFontScaleController.cancel()
                        pinchFrameBudgetSampler.cancel()
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
            LocalChatShouldDeferHeavyToolCards provides (
                state.isLoadingMessages ||
                    state.isLoadingOlderMessages ||
                    suppressPinchLayoutAnimations
                ),
        ) {
            // letta-mobile-58qlr: soft gradient fade at the top/bottom scroll
            // edges (replaces the harsh clip line). The fade target is the
            // color the chat actually draws on — mirroring the ThinkingShader
            // bgColor logic in ChatScreen — so content dissolves exactly into
            // the background. Applied on a wrapping Box (separate node) so its
            // offscreen DstIn layer doesn't clobber the LazyColumn's own
            // rasterization graphicsLayer used during pinch.
            val fadeTargetColor = chatFadeTargetColor(
                chatBackground = chatBackground,
                fallbackContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            )
            ChatFadingEdgesBox(
                listState = listState,
                targetColor = fadeTargetColor,
                modifier = Modifier.fillMaxSize(),
                // letta-mobile-58qlr.1: don't fade the bottom edge while pinned
                // to the newest message — keeps a just-sent prompt / live
                // streaming bubble fully visible instead of dimming it.
                suppressBottom = isNearBottom,
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
                        val itemSeesLiveScale = chatRenderItemSeesLiveScale(
                            isPinching = pinchFontScaleController.isPinching,
                            scaleWindowIndexRange = scaleWindowIndexRange,
                            itemIndex = index,
                        )
                        val perItemFontScale = if (itemSeesLiveScale) liveFontScale else activeFontScale
                        CompositionLocalProvider(
                            LocalChatFontScale provides perItemFontScale,
                            LocalToolCardBodyParentVisible provides itemSeesLiveScale,
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
                                    // Use the raw run id (not stableKey.removePrefix)
                                    // so collapse-state matches the RunBlock path even
                                    // when the server id already starts with "run-"
                                    // (letta-mobile-lkj4r).
                                    val runId = renderItem.stableRunId ?: stableKey.removePrefix("run-")
                                    RunBlock(
                                        messages = listOf(msg),
                                        collapsed = runId in state.collapsedRunIds,
                                        onToggleCollapsed = {
                                            // letta-mobile-<collapse-floor>: release
                                            // streaming floors once per toggle so a
                                            // collapsed streaming run re-seeds to its
                                            // smaller height (no dead space). O(1) per
                                            // rare user action; no per-frame cost.
                                            itemGeometryState.clearStreamingFloors()
                                            onToggleRunCollapsed(runId)
                                        },
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
                                            onAttachmentImageTap = onAttachmentImageTap,
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
                                        onAttachmentImageTap = onAttachmentImageTap,
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
                                    onToggleCollapsed = {
                                        // letta-mobile-<collapse-floor>: see above —
                                        // release streaming floors on collapse toggle.
                                        itemGeometryState.clearStreamingFloors()
                                        onToggleRunCollapsed(renderItem.runId)
                                    },
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
                                        onAttachmentImageTap = onAttachmentImageTap,
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
            } // letta-mobile-58qlr: end ChatFadingEdgesBox
        } // letta-mobile-5e0f.r2: end CompositionLocalProvider(LocalChatIsPinching)

        ScrollToBottomFab(
            visible = showScrollFab,
            onClick = { scope.launch { listState.animateScrollToItem(0) } },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(LettaSpacing.innerPadding),
        )

        // letta-mobile-1k3ge restore: fullscreen image viewer overlay. Opens
        // when an attachment is tapped (state set via onAttachmentImageTap),
        // supports pinch-zoom / swipe-to-dismiss; dismiss clears the state.
        imageViewerState?.let { (viewerAttachments, initialIndex) ->
            ChatImageViewer(
                attachments = viewerAttachments,
                initialPage = initialIndex,
                onDismiss = { imageViewerState = null },
            )
        }

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
    // letta-mobile-75nad: the height floor also leaks across user-driven
    // expand/collapse events for items whose expansion state is NOT in the
    // chat UI state (notably tool cards, which use local `var expanded by
    // remember` for their disclosure toggle). The streaming floor is bucket-
    // keyed (without tool card expansion in the key) and monotone-up, so
    // collapsing a tool card leaves it floored at its expanded height ->
    // dead space below the item.
    //
    // Fix: only apply the floor while the item is STREAMING. Non-streaming
    // items rely on Compose's natural measurement (the cache is still seeded
    // via onSizeChanged for streaming-stability lookups; we just don't force
    // a min size on items that aren't actively growing).
    //
    // letta-mobile-<collapse-floor>: collapsing the CURRENT (still-streaming)
    // run is an INTENTIONAL shrink, exactly like pinch — but it slips past the
    // 75nad guard because isStreaming is still true, so the monotone-up
    // streaming floor (grown to the run's EXPANDED height) keeps the collapsed
    // item floored tall, leaving dead space under the ongoing response.
    //
    // The reset is handled ONCE per collapse event at the toggle chokepoint
    // (clearStreamingFloors, called from onToggleRunCollapsed) — NOT here per
    // item per frame. This keeps the streaming hot path O(1): the floor lookup
    // below is unchanged, and after a collapse the cleared floors simply
    // re-seed from the next (smaller) measurement.
    val applyFloor = isStreaming && !isPinching
    val heightFloorPx = if (applyFloor) geometryState.heightFloorFor(signature, isStreaming) else 0
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

internal enum class ChatAutoScrollAction {
    Animate,
    Snap,
    Skip,
}

private const val StreamingAutoScrollSnapThrottleMs = 96L

internal fun autoScrollAction(
    signature: ChatAutoScrollSignature,
    isStreaming: Boolean,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    lastStreamingSnapMs: Long,
    nowMs: Long,
): ChatAutoScrollAction {
    if (!isStreaming || signature.role != "assistant") return ChatAutoScrollAction.Animate
    if (firstVisibleItemIndex != 0) return ChatAutoScrollAction.Animate
    if (abs(firstVisibleItemScrollOffset) > 12) return ChatAutoScrollAction.Animate
    if (nowMs - lastStreamingSnapMs < StreamingAutoScrollSnapThrottleMs) return ChatAutoScrollAction.Skip
    return ChatAutoScrollAction.Snap
}

// letta-mobile-4bgm3: a brand-new user message becoming the newest item means
// the user just sent something (optimistic insertion). In that case we force a
// scroll to the newest edge (item 0 with reverseLayout) so the just-sent bubble
// is always brought into view — independent of the streaming snap throttle in
// [autoScrollAction] and independent of whether the viewport was near the bottom.
//
// We require the message id to differ from the previously-handled newest id so
// that subsequent edits/streaming updates to the same user message (or assistant
// growth) don't re-trigger the force scroll and fight intentional manual
// scrolling. Only role == "user" qualifies; assistant updates flow through the
// existing pinned/throttled path so the rmzmo streaming-jank and pinned-snap
// behaviour is untouched.
internal fun shouldForceScrollOnUserSend(
    signature: ChatAutoScrollSignature,
    previousNewestMessageId: String?,
): Boolean {
    if (signature.role != "user") return false
    return signature.messageId != previousNewestMessageId
}

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
    onAttachmentImageTap: ((List<UiImageAttachment>, Int) -> Unit)? = null,
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
            onAttachmentImageTap = onAttachmentImageTap,
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
