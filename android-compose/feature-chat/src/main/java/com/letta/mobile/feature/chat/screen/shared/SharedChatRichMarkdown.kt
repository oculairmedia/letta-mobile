package com.letta.mobile.feature.chat.screen.shared

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.letta.mobile.feature.chat.render.STREAMING_CURSOR
import com.letta.mobile.feature.chat.render.shouldShowStreamingCursor
import com.letta.mobile.feature.chat.render.streamingDisplayText
import com.letta.mobile.ui.components.MarkdownText
import com.letta.mobile.ui.components.StreamingMarkdownText
import com.letta.mobile.ui.components.rememberReducedMotionEnabled
import com.letta.mobile.ui.markdown.MarkdownPaint
import com.letta.mobile.ui.markdown.SharedRichMarkdownRenderer
import com.letta.mobile.ui.theme.LocalChatFontScale

/**
 * letta-mobile-bglj6.1.16: the Android binding of the shared chat page's rich markdown seam.
 *
 * The legacy Android chat rendered markdown through the designsystem renderer — Atom-highlighted
 * code fences with a language header and copy button, KaTeX math, Mermaid diagrams, autolinked
 * bare URLs, rounded inline-code spans and the editorial block padding. The shared page's stock
 * paint dropped all of that; providing this renderer from [SharedChatPage] restores the legacy
 * look on Android while desktop and web keep the shared default.
 */
internal val SharedChatRichMarkdown: SharedRichMarkdownRenderer =
    SharedRichMarkdownRenderer { text, paint, isStreaming, modifier ->
        // The designsystem renderer scales its own sp values by LocalChatFontScale on top of the
        // density it receives. Inside the shared page the row's density override
        // (ChatRenderItemRow.WithRowFontScale) is the one true font scale, so pin the local to 1
        // or the rich markdown would double-scale against the rows around it.
        CompositionLocalProvider(LocalChatFontScale provides 1f) {
            RichMarkdownBody(text = text, paint = paint, isStreaming = isStreaming, modifier = modifier)
        }
    }

@Composable
private fun RichMarkdownBody(
    text: String,
    paint: MarkdownPaint,
    isStreaming: Boolean,
    modifier: Modifier,
) {
    // A row that streamed keeps the streaming renderer through its settle, so the final height
    // change eases in (StreamingMarkdownText applies its settle animation once isStreaming flips
    // false); a row that never streamed (hydrated history) takes the settled renderer directly.
    // SideEffect, not a write during composition: the flip frame itself must not re-render.
    var hasStreamed by remember { mutableStateOf(false) }
    SideEffect { if (isStreaming) hasStreamed = true }
    if (isStreaming || hasStreamed) {
        StreamedRichMarkdown(text = text, textColor = paint.textColor, isStreaming = isStreaming, modifier = modifier)
    } else {
        MarkdownText(text = text, modifier = modifier, textColor = paint.textColor)
    }
}

/**
 * The streaming reveal as the legacy chat ran it (AssistantResponseText): the caller pre-smooths
 * the text (the shared row's smoother plays the role of the legacy one), this renderer coalesces
 * it at paint cadence into committed blocks, holds a word-boundary tail, and draws the streaming
 * cursor — fading out over 500ms once the stream ends, instantly under reduced motion.
 */
@Composable
private fun StreamedRichMarkdown(
    text: String,
    textColor: Color,
    isStreaming: Boolean,
    modifier: Modifier,
) {
    val reducedMotion = rememberReducedMotionEnabled()
    val cursorVisible = isStreaming && shouldShowStreamingCursor(text)
    val cursorAlpha by animateFloatAsState(
        targetValue = if (cursorVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (cursorVisible || reducedMotion) 0 else CURSOR_FADE_MILLIS,
            easing = LinearEasing,
        ),
        label = "shared_chat_streaming_cursor_alpha",
    )
    StreamingMarkdownText(
        text = text,
        modifier = modifier,
        textColor = textColor,
        tailTransform = ::streamingDisplayText,
        cursorText = if (cursorAlpha > CURSOR_ALPHA_EPSILON) STREAMING_CURSOR else null,
        cursorAlpha = cursorAlpha,
        deferUnstableMarkdown = isStreaming,
        isStreaming = isStreaming,
        animateSettledSize = isStreaming,
    )
}

private const val CURSOR_FADE_MILLIS = 500
private const val CURSOR_ALPHA_EPSILON = 0.001f