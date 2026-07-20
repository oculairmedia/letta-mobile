package com.letta.mobile.feature.chat.render

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.letta.mobile.ui.components.StreamingMarkdownText
import com.letta.mobile.ui.components.rememberReducedMotionEnabled
import com.letta.mobile.ui.chat.render.RenderDiagnostics

@Composable
internal fun AssistantResponseText(props: AssistantResponseTextProps) {
    val streamingState = rememberAssistantStreamingState(
        messageId = props.messageId,
        text = props.text,
        isStreaming = props.isStreaming,
    )
    val smoothedText = rememberAssistantSmoothedText(
        messageId = props.messageId,
        text = props.text,
        isStreaming = props.isStreaming,
        streamingState = streamingState,
    )
    val cursorState = rememberStreamingCursorState(
        smoothedText = smoothedText,
        targetText = props.text,
        isStreaming = props.isStreaming,
    )
    val effectivelyStreaming = props.isStreaming || smoothedText != props.text
    androidx.compose.runtime.SideEffect {
        RenderDiagnostics.onDisplayedText(
            conversationId = "",
            site = "streaming",
            serverId = props.messageId,
            text = smoothedText,
        )
    }
    StreamingMarkdownText(
        text = smoothedText,
        textColor = props.textColor,
        tailTransform = ::streamingDisplayText,
        cursorText = if (cursorState.displayCursor) STREAMING_CURSOR else null,
        cursorAlpha = cursorState.cursorAlpha,
        deferUnstableMarkdown = cursorState.showCursor,
        stabilizeTables = streamingState.hasStreamed || streamingState.hasTable,
        isStreaming = effectivelyStreaming,
        animateSettledSize = effectivelyStreaming,
        modifier = props.modifier,
    )
}

@Composable
private fun rememberStreamingCursorState(
    smoothedText: String,
    targetText: String,
    isStreaming: Boolean,
): StreamingCursorState {
    val showCursor = isStreaming || smoothedText != targetText
    val cursorEligible = shouldShowStreamingCursor(smoothedText)
    val targetCursorVisible = showCursor && cursorEligible
    val reducedMotion = rememberReducedMotionEnabled()
    val cursorAlpha by animateFloatAsState(
        targetValue = if (targetCursorVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = cursorFadeDurationMillis(
                targetCursorVisible = targetCursorVisible,
                reducedMotion = reducedMotion,
            ),
            easing = LinearEasing,
        ),
        label = "streaming_cursor_alpha",
    )
    val displayCursor = targetCursorVisible || cursorAlpha > 0.001f
    return StreamingCursorState(
        showCursor = showCursor,
        cursorAlpha = cursorAlpha,
        displayCursor = displayCursor,
    )
}

private fun cursorFadeDurationMillis(
    targetCursorVisible: Boolean,
    reducedMotion: Boolean,
): Int = when {
    targetCursorVisible -> 0
    reducedMotion -> 0
    else -> 500
}

private data class StreamingCursorState(
    val showCursor: Boolean,
    val cursorAlpha: Float,
    val displayCursor: Boolean,
)
