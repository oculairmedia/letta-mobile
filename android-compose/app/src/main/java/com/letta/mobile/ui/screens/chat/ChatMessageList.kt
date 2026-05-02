package com.letta.mobile.ui.screens.chat

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
import java.time.LocalDate
import kotlin.math.round
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun ChatMessageList(
    state: ChatUiState,
    renderItems: List<ChatRenderItem>,
    chatMode: String,
    scrollToMessageId: String?,
    activeFontScale: Float,
    onActiveFontScaleChange: (Float) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onLoadOlderMessages: () -> Unit,
    onSendMessage: (String) -> Unit,
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

    // letta-mobile-5e0f.r3: GPU-only visual scale applied via
    // Modifier.graphicsLayer during the pinch gesture. We no longer
    // mutate fontScale on every quantized step (r2's "continuous reflow"
    // approach) — that recomposed the entire chat tree at gesture rate
    // (~5–10 fontScale changes/sec × every Text reading LocalChatTypography
    // × cascading height interpolations) which produced the high-frequency
    // strobe Emmanuel reported. Instead the chat list scales as a single
    // GPU layer (zero recomposition, zero re-measure, zero re-layout)
    // and we commit one true font-scale reflow on lift. Bias: smoothness
    // > pixel-perfect text shaping mid-gesture. Accepted UX trade-off:
    // a tiny snap on release as the layer rasters at scale 1.0 but
    // typography re-flows to the committed scale.
    var transientPinchScale by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(pinchTick) {
        if (pinchTick > 0) {
            showFontIndicator = true
            delay(1000)
            showFontIndicator = false
        }
    }

    val messageCount by rememberUpdatedState(state.messages.size)

    val isAtBottom by remember {
        derivedStateOf {
            val firstVisible = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
            firstVisible <= 1
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

    // letta-mobile-d2z6 follow-up: the streaming/animated scroll branch
    // introduced a duplicate-flash on stream end (the LaunchedEffect
    // re-emitted as isStreaming flipped, racing with the bubble's settled
    // layout). Reverted to the original animateScrollToItem behaviour;
    // bubble flicker is being addressed at the rendering layer instead.
    LaunchedEffect(Unit) {
        snapshotFlow { messageCount }
            .distinctUntilChanged()
            .collect {
                if (it > 0 && isAtBottom && scrollToMessageId == null) {
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
                    isStreaming = state.isStreaming,
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
                // letta-mobile-5e0f.r3: GPU-only pinch.
                //
                // Why this approach: r2 tried "continuous reflow"
                // (push fontScale to the theme on every quantized
                // 2% step). Even with memoized ChatTypography +
                // compositionLocalOf + animateContentSize gating,
                // every fontScale tick re-measured every Text in
                // every visible bubble at a new size — and on a
                // device with markdown/code blocks/tool cards
                // that's a measurable repaint. At 5–10 ticks/sec
                // the result was the high-frequency strobe
                // Emmanuel reported.
                //
                // r3 fix: during the gesture we ONLY mutate
                // transientPinchScale, which is wired to a single
                // Modifier.graphicsLayer { scaleX/scaleY } on the
                // chat-list Box. graphicsLayer is GPU compositor
                // work — no recomposition, no remeasure, no
                // relayout, no Text re-shaping. The chat scales
                // as a single textured layer at native frame
                // rate.
                //
                // On gesture commit (lift) we:
                //   1. Compute the final snapped fontScale.
                //   2. Push it through onActiveFontScaleChange
                //      so the theme reflows ONCE.
                //   3. Reset transientPinchScale to 1f in the
                //      same recomposition so the layer un-scales
                //      as the new typography lays out — a single
                //      visible "snap" instead of 45 stuttering
                //      reflows.
                //
                // Trade-off: mid-gesture the text is rastered at
                // scale 1.0 and stretched, so glyphs are slightly
                // softer than a true reflow would render. This is
                // the same trade-off iOS Mail / Messages / Slack
                // make. Accepted in exchange for smoothness.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var gesturePinching = false
                    // The committed (already-applied to theme) scale
                    // at the start of the gesture. The visual layer
                    // multiplies further pinch deltas on top.
                    val baseScale = activeFontScale
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
                                liveZoom *= zoom
                                // Clamp the visual layer to the
                                // committable range (relative to
                                // baseScale) so the user can't
                                // scale past what we'll snap to.
                                val targetScale = (baseScale * liveZoom).coerceIn(0.7f, 1.6f)
                                liveZoom = targetScale / baseScale
                                transientPinchScale = liveZoom
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (gesturePinching) {
                        // Snap to 2% step on commit. Quantization
                        // happens once on lift — not 45 times
                        // during the gesture.
                        val step = 0.02f
                        val finalRaw = (baseScale * liveZoom).coerceIn(0.7f, 1.6f)
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
                // letta-mobile-23h5 (polish 2026-04-19): wider side
                // gutters so bubbles don't kiss the screen edge.
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                reverseLayout = true,
                // letta-mobile-5e0f.r3: GPU-only pinch scale. During
                // a pinch gesture transientPinchScale ranges 0.7..1.6
                // (relative to the committed fontScale baseline) and
                // is rendered as a single uniform layer transform.
                // At rest it's exactly 1f and graphicsLayer becomes
                // a no-op (Compose elides identity transforms). The
                // transformOrigin is the visual centre of the list
                // so pinch feels like it's happening at the focal
                // point of the user's fingers (close enough — true
                // focal-point scaling would require tracking the
                // gesture centroid; this is the standard messaging
                // app behaviour and feels natural).
                modifier = Modifier.graphicsLayer {
                    scaleX = transientPinchScale
                    scaleY = transientPinchScale
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                },
            ) {
                if (state.isStreaming) {
                    item(key = "typing") {
                        TypingIndicator(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    }
                }

                renderItems.forEachIndexed { index, renderItem ->
                    val prevDate = renderItems.getOrNull(index + 1)?.boundaryTimestamp?.take(10)
                    val currentDate = renderItem.boundaryTimestamp.take(10)
                    val showDate = prevDate != null && prevDate != currentDate

                    item(key = renderItem.key) {
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
                                    ) { message, position, rowModifier ->
                                        RenderChatMessage(
                                            message = message,
                                            position = position,
                                            state = state,
                                            chatMode = chatMode,
                                            highlightedMessageId = highlightedMessageId,
                                            onSendMessage = onSendMessage,
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
                                ) { message, position, rowModifier ->
                                    RenderChatMessage(
                                        message = message,
                                        position = position,
                                        state = state,
                                        chatMode = chatMode,
                                        highlightedMessageId = highlightedMessageId,
                                        onSendMessage = onSendMessage,
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
                        item(key = "date-${renderItem.key}") {
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
            // letta-mobile-5e0f.r2: activeFontScale is now updated
            // continuously during the gesture (memoized typography
            // makes that cheap), so the indicator reads it directly.
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

fun calculateLazyIndexForRenderItem(
    targetRenderIndex: Int,
    renderItems: List<ChatRenderItem>,
    isStreaming: Boolean,
): Int {
    var lazyIndex = if (isStreaming) 1 else 0
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
                    fontFamily = FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
