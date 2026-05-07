package com.letta.mobile.ui.screens.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun rememberSmoothedStreamingText(
    rawText: String,
    isStreaming: Boolean,
): String {
    // When the stream ends, snap to the full text immediately — no drain
    // animation that would cause a layout jump at completion.
    if (!isStreaming) return rawText

    val smoother = remember { StreamingDisplayTextSmoother() }
    var displayedText by remember { mutableStateOf("") }

    smoother.updateTarget(rawText, isStreaming, System.nanoTime() / 1_000_000L)

    LaunchedEffect(rawText, isStreaming) {
        while (isActive && !smoother.isFullyRevealed) {
            displayedText = smoother.step(System.nanoTime() / 1_000_000L)
            delay(FRAME_INTERVAL_MS)
        }
        displayedText = smoother.step(System.nanoTime() / 1_000_000L)
    }

    return displayedText
}

private const val FRAME_INTERVAL_MS = 8L
