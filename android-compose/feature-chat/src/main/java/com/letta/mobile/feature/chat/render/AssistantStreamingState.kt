package com.letta.mobile.feature.chat.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

internal data class AssistantResponseTextProps(
    val messageId: String,
    val text: String,
    val textColor: Color,
    val modifier: Modifier = Modifier,
    val isStreaming: Boolean,
)

internal data class AssistantStreamingState(
    val initialText: String,
    val textHasGrown: Boolean,
    val hasStreamed: Boolean,
    val hasTable: Boolean,
    val useStreamingRenderer: Boolean,
)

@Composable
internal fun rememberAssistantStreamingState(
    messageId: String,
    text: String,
    isStreaming: Boolean,
): AssistantStreamingState {
    var hasStreamed by remember(messageId) { mutableStateOf(false) }
    val initialText = remember(messageId) { text }
    val textHasGrown = text.length > initialText.length
    LaunchedEffect(isStreaming, textHasGrown) {
        if (isStreaming && textHasGrown) hasStreamed = true
    }
    val hasTable = remember(text) { text.containsMarkdownTable() }
    val useStreamingRenderer = (isStreaming && textHasGrown) || hasStreamed
    return AssistantStreamingState(
        initialText = initialText,
        textHasGrown = textHasGrown,
        hasStreamed = hasStreamed,
        hasTable = hasTable,
        useStreamingRenderer = useStreamingRenderer,
    )
}

@Composable
internal fun rememberAssistantSmoothedText(
    messageId: String,
    text: String,
    isStreaming: Boolean,
    streamingState: AssistantStreamingState,
): String {
    if (!streamingState.useStreamingRenderer) return text

    var lastHapticRevealLength by remember(messageId) {
        mutableStateOf(streamingState.initialText.length)
    }
    val streamingRevealHapticPulse = LocalStreamingRevealHapticPulse.current
    return com.letta.mobile.ui.chat.render.rememberSmoothedStreamingText(
        rawText = text,
        isStreaming = isStreaming && streamingState.textHasGrown,
        seedText = streamingState.initialText,
        onRevealStep = { revealedText ->
            if (!shouldPulseForStreamingReveal(
                    previousLength = lastHapticRevealLength,
                    revealedText = revealedText,
                )
            ) {
                return@rememberSmoothedStreamingText
            }
            lastHapticRevealLength = revealedText.length
            streamingRevealHapticPulse()
        },
    )
}
