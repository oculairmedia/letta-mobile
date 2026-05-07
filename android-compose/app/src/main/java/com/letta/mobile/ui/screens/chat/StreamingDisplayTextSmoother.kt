package com.letta.mobile.ui.screens.chat

/**
 * Meters bursty streaming-text arrivals into a smooth reveal by advancing a
 * visible character prefix at a steady cadence.
 *
 * Compared to the flk2 word-token version:
 * - Character-based (no regex token rebuild on every content change)
 * - Clock is NOT reset on target updates — pacing stays independent of
 *   delta arrival frequency, preventing the "speed-up on every chunk" bug
 * - Faster default cadence: 10 ms/char when streaming (was 30 ms/word)
 * - Monotonic char index eliminates token-rebuild race conditions
 */
class StreamingDisplayTextSmoother(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private var target: String = ""
    private var revealedLength: Int = 0
    private var nextRevealAfterMs: Long = -1L
    private var streaming: Boolean = false

    fun updateTarget(text: String, isStreaming: Boolean, nowMs: Long) {
        if (text != target) {
            target = text
            // If text shrank (e.g. dedup) clamp the revealed prefix
            if (revealedLength > text.length) {
                revealedLength = text.length
            }
        }
        streaming = isStreaming
        if (nextRevealAfterMs < 0L) {
            nextRevealAfterMs = nowMs
        }
    }

    /**
     * Advance the visible prefix by one character if enough time has
     * elapsed, then return the substring to display.
     */
    fun step(nowMs: Long): String {
        if (target.isEmpty()) return ""

        val interval = if (streaming) intervalMs else intervalMs / DRAIN_DIVISOR

        // Advance one char per interval. Catch up in bursts if we fell
        // behind (sleep/wake / slow frame).
        while (nowMs >= nextRevealAfterMs && revealedLength < target.length) {
            revealedLength++
            nextRevealAfterMs += interval
        }

        // Clamp — first call or clock skew
        if (revealedLength > target.length) {
            revealedLength = target.length
        }
        return target.substring(0, revealedLength)
    }

    val isFullyRevealed: Boolean
        get() = revealedLength >= target.length

    companion object {
        /** 10 ms per char — ~100 chars/sec, ahead of typical LLM output. */
        const val DEFAULT_INTERVAL_MS = 10L

        /** Drain 3× faster when stream ends. */
        private const val DRAIN_DIVISOR = 3L
    }
}
