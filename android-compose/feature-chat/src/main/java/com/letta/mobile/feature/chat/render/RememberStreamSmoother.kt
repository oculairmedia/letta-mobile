package com.letta.mobile.feature.chat.render

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
 * remaining tail continues at the current smoothed cadence until the full
 * text is visible.
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
internal fun rememberSmoothedStreamingText(
    rawText: String,
    isStreaming: Boolean,
    seedText: String = "",
): String {
    val smoother = remember { StreamingDisplayTextSmoother() }

    // letta-mobile-uoiu6: seed the smoother (and the initial displayed value)
    // with the prefix that was already painted before streaming engaged.
    // Without this, the smoother starts at revealedCount=0 and re-reveals the
    // already-visible first word from an empty string, producing the visible
    // "first word flash". The seed only takes effect on the very first
    // composition / before the smoother has begun revealing.
    val initialDisplayed = remember { seedText }
    var displayedText by remember { mutableStateOf(initialDisplayed) }
    remember {
        smoother.seed(seedText, isStreaming, System.nanoTime() / 1_000_000L)
        true
    }

    // Push every rawText / isStreaming change into the smoother.
    val nowMs = { System.nanoTime() / 1_000_000L }
    smoother.updateTarget(rawText, isStreaming, nowMs())

    // Paint loop: runs while the smoother hasn't fully caught up.
    // Use the same cadence as StreamingMarkdownText's markdown coalescer so
    // one raw chunk produces at most one visible text tree/layout update per
    // paint window instead of a 60fps stream of mostly redundant substring
    // writes. This keeps long-history streaming focused on chunk/paint cadence
    // while the pure smoother still estimates velocity from monotonic time.
    LaunchedEffect(rawText, isStreaming) {
        while (isActive && !(smoother.isFullyRevealed && !isStreaming)) {
            displayedText = smoother.step(nowMs())
            delay(STREAMING_TEXT_PAINT_INTERVAL_MS)
        }
        // Final step to ensure we don't leave a partial reveal.
        displayedText = smoother.step(nowMs())
    }

    return displayedText
}

/** Shared visible streaming-text cadence, aligned with StreamingMarkdownText. */
internal const val STREAMING_TEXT_PAINT_INTERVAL_MS = 50L
