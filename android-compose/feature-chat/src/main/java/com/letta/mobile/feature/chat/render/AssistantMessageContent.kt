package com.letta.mobile.feature.chat.render

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.letta.mobile.ui.components.StreamingMarkdownText
import com.letta.mobile.ui.components.rememberReducedMotionEnabled
import com.letta.mobile.ui.chat.render.RenderDiagnostics
import com.letta.mobile.ui.chat.render.rememberSmoothedStreamingText

@Composable
internal fun AssistantResponseText(
    messageId: String,
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier,
    isStreaming: Boolean,
) {
    // letta-mobile-yh0c: only treat the message as actively streaming once
    // its text has actually GROWN since first composition. The upstream
    // `isStreaming` flag flips true the moment the user taps Send, even
    // before the optimistic user Local is appended — during that gap,
    // ChatMessageList's per-message `isStreaming = state.isStreaming &&
    // message.id == lastOrNull?.id` momentarily lights up the PRIOR
    // assistant response. Without the growth gate, the smoother engaged
    // for that prior message and visually replayed its full content. By
    // anchoring `hasStreamed` to real text growth, a momentarily-true
    // isStreaming with unchanged text no longer kicks off the smoother.
    var hasStreamed by remember(messageId) { mutableStateOf(false) }
    val initialText = remember(messageId) { text }
    val textHasGrown = text.length > initialText.length
    LaunchedEffect(isStreaming, textHasGrown) {
        if (isStreaming && textHasGrown) hasStreamed = true
    }
    val hasTable = remember(text) { text.containsMarkdownTable() }
    val streamingRevealHapticPulse = LocalStreamingRevealHapticPulse.current
    var lastHapticRevealLength by remember(messageId) { mutableStateOf(initialText.length) }

    // Gate every "use the streaming renderer / smoother" decision on real
    // text growth, not just the bare `isStreaming` flag. The flag flickers
    // true the moment the user taps Send (before the new user Local is
    // appended), and any path that engages the smoother during that window
    // wipes the prior message's `displayed` back to "" and replays it.
    // `useStreamingRenderer` is the single decision point — short-circuit
    // and smoother gating both flow from it so the renderer choice stays
    // stable across the flicker.
    val useStreamingRenderer = (isStreaming && textHasGrown) || hasStreamed

    val smoothedText = if (useStreamingRenderer) {
        // letta-mobile-uoiu6: seed the smoother with the text that was already
        // painted via the plain MarkdownText path on the first composition
        // (`initialText`). When the first delta makes the message grow and we
        // switch into the streaming renderer, seeding makes the reveal continue
        // from the already-visible prefix instead of rewinding to an empty
        // string and re-growing the first word — which is the visible
        // "first word flash". The seed is captured once and only applies on the
        // smoother's first engage.
        rememberSmoothedStreamingText(
            rawText = text,
            isStreaming = isStreaming && textHasGrown,
            seedText = initialText,
            onRevealStep = { revealedText ->
                val revealedLength = revealedText.length
                if (shouldPulseForStreamingReveal(
                        previousLength = lastHapticRevealLength,
                        revealedText = revealedText,
                    )
                ) {
                    lastHapticRevealLength = revealedLength
                    streamingRevealHapticPulse()
                }
            },
        )
    } else {
        text
    }
    // letta-mobile-d9zy.5 (retry): cursor fade-out.
    // `showCursor` decides whether the cursor SHOULD be visible based on
    // whether content is still arriving (isStreaming) or the smoother is
    // still catching up. `cursorAlpha` keeps the cursor character in the tree
    // during the post-stream grace window so it does not hard-cut at the same
    // moment the text catches up.
    val showCursor = isStreaming || smoothedText != text
    val cursorEligible = shouldShowStreamingCursor(smoothedText)
    val targetCursorVisible = showCursor && cursorEligible
    val reducedMotion = rememberReducedMotionEnabled()
    val cursorAlpha by animateFloatAsState(
        targetValue = if (targetCursorVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = when {
                targetCursorVisible -> 0
                reducedMotion -> 0
                else -> 500
            },
            easing = LinearEasing,
        ),
        label = "streaming_cursor_alpha",
    )
    val displayCursor = targetCursorVisible || cursorAlpha > 0.001f
    // Keep the same streaming renderer for messages that streamed in this composition even after
    // the smoother catches up. Swapping to settled MarkdownText at stream termination causes a
    // final parsed-subtree/spacing handoff flash; hydrated messages still use MarkdownText because
    // hasStreamed is false for them. Once caught up after stream end, hide the cursor while keeping
    // the stable streaming layout.
    // letta-mobile-9hcg.b: pass an effectively-streaming bit that stays
    // true while the smoother is still catching up. Without this, the
    // moment `isStreaming` flips false the height animation collapses
    // mid-catch-up and the bubble flashes back to the smoothed length.
    val effectivelyStreaming = isStreaming || smoothedText != text
    androidx.compose.runtime.SideEffect {
        RenderDiagnostics.onDisplayedText(
            conversationId = "",
            site = "streaming",
            serverId = messageId,
            text = smoothedText,
        )
    }
    StreamingMarkdownText(
        text = smoothedText,
        textColor = textColor,
        tailTransform = ::streamingDisplayText,
        cursorText = if (displayCursor) STREAMING_CURSOR else null,
        cursorAlpha = cursorAlpha,
        deferUnstableMarkdown = showCursor,
        stabilizeTables = hasStreamed || hasTable,
        isStreaming = effectivelyStreaming,
        animateSettledSize = effectivelyStreaming,
        modifier = modifier,
    )
}
