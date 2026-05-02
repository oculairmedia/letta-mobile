package com.letta.mobile.ui.screens.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Composable-side wrapper around [StreamingDisplayTextSmoother].
 *
 * Returns a smoothed version of [rawText] that reveals characters at a
 * steady cadence while [isStreaming] is true. When the stream ends, the
 * remaining tail drains at an accelerated rate and the function returns
 * the full text.
 *
 * Usage:
 * ```
 * val smoothed = rememberSmoothedStreamingText(
 *     rawText = message.content,
 *     isStreaming = isStreaming,
 * )
 * Text(text = smoothed)
 * ```
 *
 * The frame loop only runs while text is being revealed. Once the smoother
 * is fully caught up and the stream is closed, the loop suspends — no
 * wasted frames at rest.
 */
@Composable
fun rememberSmoothedStreamingText(
    rawText: String,
    isStreaming: Boolean,
): String {
    val smoother = remember { StreamingDisplayTextSmoother() }
    var displayedText by remember { mutableStateOf("") }

    // Push every rawText / isStreaming change into the smoother.
    val nowMs = { System.nanoTime() / 1_000_000L }
    smoother.updateTarget(rawText, isStreaming, nowMs())

    // Frame loop: runs while the smoother hasn't fully caught up.
    // Uses a ~16 ms tick (≈ 60 fps) to call step(). The loop
    // naturally suspends once isFullyRevealed && !isStreaming,
    // because the LaunchedEffect's key (rawText, isStreaming)
    // won't change until the next user send.
    LaunchedEffect(rawText, isStreaming) {
        while (isActive && !(smoother.isFullyRevealed && !isStreaming)) {
            displayedText = smoother.step(nowMs())
            delay(FRAME_INTERVAL_MS)
        }
        // Final step to ensure we don't leave a partial reveal.
        displayedText = smoother.step(nowMs())
    }

    return displayedText
}

/** Target frame interval — 16 ms ≈ 60 fps. */
private const val FRAME_INTERVAL_MS = 16L
