package com.letta.mobile.ui.chat.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
fun rememberSmoothedStreamingText(
    rawText: String,
    isStreaming: Boolean,
    seedText: String = "",
    onRevealStep: ((String) -> Unit)? = null,
): String {
    val smoother = remember { StreamingDisplayTextSmoother() }
    val currentOnRevealStep by rememberUpdatedState(onRevealStep)

    // letta-mobile-uoiu6: seed the smoother (and the initial displayed value)
    // with the prefix that was already painted before streaming engaged.
    // Without this, the smoother starts at revealedCount=0 and re-reveals the
    // already-visible first word from an empty string, producing the visible
    // "first word flash". The seed only takes effect on the very first
    // composition / before the smoother has begun revealing.
    val initialDisplayed = remember { seedText }
    var displayedText by remember { mutableStateOf(initialDisplayed) }
    // letta-mobile-1kz40 (head-clip / order race): seed() runs exactly once, in
    // a remember{} block that executes during composition BEFORE the inline
    // updateTarget() below. seed() records the provisional prefix as the cursor
    // but it is NOT trusted as a verified prefix of the real target — the very
    // next updateTarget() re-verifies the cursor against the actual rawText via
    // the longest-common-prefix clamp inside the smoother. So even if seedText
    // is stale or not a prefix of rawText (the head-clip cause), the cursor is
    // clamped back to a true prefix and the head is never dropped. Folding the
    // verification into updateTarget makes the seed/update ordering unable to
    // clip the head.
    val nowMs = { System.nanoTime() / 1_000_000L }
    remember {
        smoother.seed(seedText, isStreaming, nowMs())
        true
    }

    // Push every rawText / isStreaming change into the smoother. This always
    // runs after the one-time seed above (same composition pass), so the
    // first target the smoother sees re-clamps any provisional seed cursor.
    smoother.updateTarget(rawText, isStreaming, nowMs())

    // Paint loop: runs while the smoother hasn't fully caught up.
    // Use the same cadence as StreamingMarkdownText's markdown coalescer so
    // one raw chunk produces at most one visible text tree/layout update per
    // paint window instead of a 60fps stream of mostly redundant substring
    // writes. This keeps long-history streaming focused on chunk/paint cadence
    // while the pure smoother still estimates velocity from monotonic time.
    LaunchedEffect(rawText, isStreaming) {
        while (isActive && !(smoother.isFullyRevealed && !isStreaming)) {
            val nextText = smoother.step(nowMs())
            if (nextText.length > displayedText.length) {
                currentOnRevealStep?.invoke(nextText)
            }
            displayedText = nextText
            delay(STREAMING_TEXT_PAINT_INTERVAL_MS)
        }
        // Final step to ensure we don't leave a partial reveal.
        val finalText = smoother.step(nowMs())
        if (finalText.length > displayedText.length) {
            currentOnRevealStep?.invoke(finalText)
        }
        displayedText = finalText
    }

    return displayedText
}

/** Shared visible streaming-text cadence, aligned with StreamingMarkdownText. */
const val STREAMING_TEXT_PAINT_INTERVAL_MS = 50L
