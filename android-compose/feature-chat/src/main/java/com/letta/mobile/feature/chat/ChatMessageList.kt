package com.letta.mobile.feature.chat

import com.letta.mobile.ui.theme.LettaCodeFont

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.components.DateSeparator
import com.letta.mobile.ui.components.ScrollToBottomFab
import com.letta.mobile.ui.components.TypingIndicator
import com.letta.mobile.ui.theme.LocalChatIsPinching
import com.letta.mobile.ui.theme.chatDimens
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.round
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
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var hasScrolledToTarget by remember { mutableStateOf(false) }
    var showFontIndicator by remember { mutableStateOf(false) }
    var pinchTick by remember { mutableStateOf(0L) }
    // letta-mobile-5e0f.r2: pinch-active flag plumbed via
    // LocalChatIsPinching so animateContentSize sites in the chat tree
    // can suppress themselves during the gesture (and re-enable for
    // their normal triggers like collapse/expand). True from the first
    // 2-finger event in a gesture until the user lifts.
    var isPinching by remember { mutableStateOf(false) }

    // letta-mobile-d9zy.1: throttled hybrid reflow for pinch-to-zoom.
    // Modifier.graphicsLayer scales the list every pointer frame (zero
    // recomposition, zero remeasure — pure GPU compositor work). In
    // addition, a LaunchedEffect runs a 100 ms periodic checkpoint: if
    // transientPinchScale has drifted more than 5% from 1.0f it fires
    // onActiveFontScaleChange mid-gesture and simultaneously resets
    // transientPinchScale to 1.0f, so the GPU layer un-scales exactly as
    // the new typography lays out — no visible jump. This gives a bounded
    // number of text reflows (at most ~10/sec) while keeping frame pacing
    // smooth at the GPU layer rate. The final snap on lift still persists
    // the setting via onFontScaleChange.
    var transientPinchScale by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(pinchTick) {
        if (pinchTick > 0) {
            showFontIndicator = true
            delay(1000)
            showFontIndicator = false
        }
    }

    // letta-mobile-d9zy.1: mid-gesture hybrid checkpoint.
    // While isPinching is true, poll every 100 ms. If transientPinchScale
    // has drifted more than 5% from the reset baseline of 1.0f, snap the
    // committed fontScale to the current visual scale and reset
    // transientPinchScale to 1.0f so the GPU layer returns to identity at
    // the same moment the text reflows — preventing a visible jump.
    //
    // midGestureBase tracks the committed scale after each checkpoint so the
    // gesture loop can rebase liveZoom against it, ensuring the GPU layer
    // stays at 1.0f immediately after a commit rather than snapping back to
    // the pre-commit accumulated value on the next pointer frame.
    var midGestureBase by remember { mutableFloatStateOf(1f) }
    val currentOnActiveFontScaleChange by rememberUpdatedState(onActiveFontScaleChange)
    LaunchedEffect(isPinching) {
        if (!isPinching) return@LaunchedEffect
        while (true) {
            delay(100)
            // Guard: gesture may have ended during the delay.
            if (!isPinching) break
            val scale = transientPinchScale
            if (abs(scale - 1f) > 0.05f) {
                val step = 0.02f
                val raw = (midGestureBase * scale).coerceIn(0.7f, 1.6f)
                val snapped = (round(raw / step) * step).coerceIn(0.7f, 1.6f)
                // Atomic flip: commit new font scale and return the GPU layer
                // to identity in the same state mutation so there is no frame
                // where both the old text layout and a non-unity graphicsLayer
                // are visible simultaneously. Also update midGestureBase so
                // the gesture loop rebases liveZoom from here.
                currentOnActiveFontScaleChange(snapped)
                midGestureBase = snapped
                transientPinchScale = 1f
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // letta-mobile-d9zy.1: throttled hybrid pinch-to-zoom.
                // transientPinchScale drives a Modifier.graphicsLayer every
                // pointer frame (GPU compositor work — no recomposition, no
                // remeasure). A separate LaunchedEffect fires
                // onActiveFontScaleChange at most every 100 ms when the
                // scale delta exceeds 5%, simultaneously resetting
                // transientPinchScale to 1.0f so the GPU layer un-scales as
                // the new text layout takes over. On lift we do a final snap
                // and persist via onFontScaleChange.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var gesturePinching = false
                    // Seed midGestureBase from the current committed scale
                    // so mid-gesture checkpoints compute against a fresh
                    // baseline. The LaunchedEffect advances midGestureBase
                    // after each checkpoint; the gesture loop reads it via
                    // the outer mutableFloatStateOf so liveZoom is always
                    // relative to the most-recently-committed scale.
                    midGestureBase = activeFontScale
                    // Live raw zoom accumulator — drives the
                    // graphicsLayer directly so the user sees
                    // every pointer frame.
                    var liveZoom = 1f
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val activePointers = event.changes.filter { it.pressed }
                        if (activePointers.size >= 2) {
                            if (!gesturePinching) {
                                gesturePinching = true
                                isPinching = true
                                pinchTick = System.nanoTime()
                            }
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                event.changes.forEach { it.consume() }
                                // If the LaunchedEffect fired a mid-gesture
                                // checkpoint since the last frame, midGestureBase
                                // will have advanced and transientPinchScale will
                                // have been reset to 1.0f. Rebase liveZoom from
                                // transientPinchScale (the GPU layer's current
                                // factor) so we accumulate only the new delta
                                // since the last checkpoint, not the full
                                // history since gesture start.
                                val currentBase = midGestureBase
                                liveZoom = transientPinchScale * zoom
                                // Clamp the visual layer to the
                                // committable range (relative to
                                // currentBase) so the user can't
                                // scale past what we'll snap to.
                                val targetScale = (currentBase * liveZoom).coerceIn(0.7f, 1.6f)
                                liveZoom = targetScale / currentBase
                                transientPinchScale = liveZoom
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (gesturePinching) {
                        // Snap to 2% step on commit. Quantization
                        // happens once on lift — not 45 times
                        // during the gesture.
                        val step = 0.02f
                        val finalRaw = (midGestureBase * liveZoom).coerceIn(0.7f, 1.6f)
                        val snapped = (round(finalRaw / step) * step)
                            .coerceIn(0.7f, 1.6f)
                        // Atomic-ish flip: theme + scale layer
                        // reset in the same recomposition pass.
                        onActiveFontScaleChange(snapped)
                        onFontScaleChange(snapped)
                        transientPinchScale = 1f
                        pinchTick = System.nanoTime()
                        isPinching = false
                    }
                }
            },
    ) {
        // letta-mobile-5e0f.r2: provide LocalChatIsPinching to
        // the entire chat content tree so animateContentSize
        // sites can suppress themselves during the gesture.
        CompositionLocalProvider(LocalChatIsPinching provides isPinching) {
            LazyColumn(
                state = listState,
                // Use the chat theme's compact gutter so assistant prose,
                // tool output, and run blocks get the widest useful line
                // length without touching the screen edge.
                contentPadding = PaddingValues(
                    horizontal = MaterialTheme.chatDimens.contentPaddingHorizontal,
                    vertical = 8.dp,
                ),
                reverseLayout = true,
                // letta-mobile-5e0f.r3: GPU-backed pinch scale. During
                // a pinch gesture transientPinchScale ranges 0.7..1.6
                // (relative to the committed fontScale baseline) and
                // is rendered as a single uniform layer transform.
                // At rest it's exactly 1f and graphicsLayer becomes
                // a no-op (Compose elides identity transforms). The
                // transformOrigin is the visual centre of the list
                // so pinch feels close to the user's focal point.
                // True
                // focal-point scaling would require tracking the
                // gesture centroid and compensating list offset; that is
                // separate from the text-reflow decision.
                modifier = Modifier.graphicsLayer {
                    scaleX = transientPinchScale
                    scaleY = transientPinchScale
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                },
            ) {
                item(key = "typing") {
                    AnimatedVisibility(
                        visible = state.isStreaming,
                        enter = ChatMotion.expandEnter(),
                        exit = ChatMotion.expandExit(),
                    ) {
                        TypingIndicator(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    }
                }

                renderItems.forEachIndexed { index, renderItem ->
                    val prevDate = renderItems.getOrNull(index + 1)?.boundaryTimestamp?.take(10)
                    val currentDate = renderItem.boundaryTimestamp.take(10)
                    val showDate = prevDate != null && prevDate != currentDate

                    item(key = renderItem.key, contentType = when (renderItem) {
                        is ChatRenderItem.Single -> "single"
                        is ChatRenderItem.RunBlock -> "runblock"
                    }) {
                        // letta-mobile-lbur follow-up: log render item keys for dedup analysis
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
                                        modifier = Modifier.padding(top = 6.dp, bottom = 0.dp),
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
                                        RoundedCornerShape(12.dp),
                                    )
                                } else {
                                    Modifier
                                }
                                RunBlock(
                                    messages = renderItem.messages.map { it.first },
                                    collapsed = renderItem.runId in state.collapsedRunIds,
                                    onToggleCollapsed = { onToggleRunCollapsed(renderItem.runId) },
                                    modifier = highlightModifier.padding(top = 6.dp, bottom = 0.dp),
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
                                .padding(vertical = 12.dp),
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
                .padding(16.dp),
        )

        if (showFontIndicator) {
            // The indicator reflects the committed font scale. During the
            // active gesture, visual scaling comes from transientPinchScale;
            // activeFontScale changes when the pinch commits on lift.
            Text(
                text = "${(activeFontScale * 100).toInt()}%",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
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
        position == GroupPosition.Middle || position == GroupPosition.Last -> 2.dp
        else -> 6.dp
    }
    val spacingAbove = if (message.isReasoning) 12.dp else 0.dp
    val isHighlighted = message.id == highlightedMessageId
    val highlightModifier = if (isHighlighted) {
        Modifier.background(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            RoundedCornerShape(12.dp),
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
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = "${message.role} | ${message.id}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
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
