package com.letta.mobile.ui.screens.chat

/**
 * Meters out bursty streaming-text arrivals at a steady per-character
 * cadence so the chat bubble grows at a readable pace instead of jumping
 * in visible chunks.
 *
 * Pure Kotlin — no Compose dependency — so it is unit-testable.
 *
 * Usage:
 *  1. Call [updateTarget] every time the ViewModel pushes new accumulated
 *     text (or flips `isStreaming` off).
 *  2. Call [step] once per frame (driven by the composable wrapper's
 *     `LaunchedEffect` frame loop) to get the substring to render.
 *
 * Design rationale (letta-mobile-d2z6.s1 follow-up):
 *  - The lettabot WS gateway emits assistant deltas at 80–150 ms
 *    intervals with variable sizes (1–140 chars, larger with server-side
 *    coalescing). Rendering them immediately causes visible jumps.
 *  - This smoother reveals characters at a steady rate
 *    ([charsPerMs] ≈ 0.12 → ~2 chars per 16 ms frame → ~120 chars/sec)
 *    absorbing burst variance.
 *  - When the stream ends ([isStreaming] = false) a faster drain rate
 *    ([DRAIN_MULTIPLIER]) ensures the tail completes promptly.
 */
class StreamingDisplayTextSmoother(
    private val charsPerMs: Float = DEFAULT_CHARS_PER_MS,
) {
    private var target: String = ""
    private var revealedCount: Int = 0
    private var lastStepMs: Long = -1L
    private var streaming: Boolean = false

    /**
     * Push the latest accumulated text from the ViewModel.
     *
     * @param text       full accumulated assistant text so far.
     * @param isStreaming true while the stream is still open.
     * @param nowMs      current monotonic time (e.g. `System.nanoTime() / 1_000_000`
     *                   or a test-supplied clock).
     */
    fun updateTarget(text: String, isStreaming: Boolean, nowMs: Long) {
        if (text != target) {
            if (!text.startsWith(target)) {
                // Text was rewritten (not extended) — reset reveal.
                revealedCount = 0
            }
            // else: text is a continuation — keep revealedCount as-is so
            // the reveal continues from where it left off.
            target = text
        }
        streaming = isStreaming
        if (lastStepMs < 0L) {
            lastStepMs = nowMs
        }
    }

    /**
     * Advance the reveal cursor and return the substring to render.
     *
     * Should be called once per frame. When the reveal is fully caught up
     * and the stream is closed, returns the full target and the caller can
     * suspend the frame loop.
     *
     * @param nowMs current monotonic time.
     * @return the prefix of [target] to display this frame.
     */
    fun step(nowMs: Long): String {
        if (target.isEmpty()) return ""

        val elapsed = if (lastStepMs >= 0L) (nowMs - lastStepMs).coerceAtLeast(0L) else 0L
        lastStepMs = nowMs

        val rate = if (streaming) charsPerMs else charsPerMs * DRAIN_MULTIPLIER
        val advance = (elapsed * rate).toInt().coerceAtLeast(1)
        revealedCount = (revealedCount + advance).coerceAtMost(target.length)

        return target.substring(0, revealedCount)
    }

    /** True when the full target has been revealed. */
    val isFullyRevealed: Boolean
        get() = revealedCount >= target.length

    companion object {
        /**
         * Default reveal rate. ~0.12 chars/ms → ~2 chars per 16 ms frame
         * → ~120 chars/sec sustained. Matches typical assistant throughput
         * of ~40 tokens/sec × ~4 chars/token ≈ 160 chars/sec, with enough
         * headroom that the reveal never lags far behind the stream.
         */
        const val DEFAULT_CHARS_PER_MS = 0.12f

        /**
         * After the stream ends, drain the remaining tail at this multiple
         * of the normal rate so the user sees the full response quickly.
         */
        private const val DRAIN_MULTIPLIER = 3f
    }
}
