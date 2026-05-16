package com.letta.mobile.feature.chat

/**
 * Meters out bursty streaming-text arrivals with a reveal velocity that follows the upstream
 * token cadence instead of using a fixed speed or an accelerated end-of-stream drain.
 *
 * Pure Kotlin — no Compose dependency — so it is unit-testable.
 *
 * Usage:
 *  1. Call [updateTarget] every time the ViewModel pushes new accumulated text (or flips
 *     `isStreaming` off).
 *  2. Call [step] once per frame (driven by the composable wrapper's `LaunchedEffect`) to get the
 *     substring to render.
 *
 * Design rationale:
 *  - Letta/lettabot can emit uneven chunks: small token deltas, then larger coalesced bursts.
 *  - Rendering chunks immediately jumps; a fixed reveal rate eventually lags and then appears to
 *    dump buffered text.
 *  - This smoother estimates incoming characters/ms from target growth, clamps it to a readable
 *    range, and tweens the visible reveal velocity toward that estimate. The result tracks token
 *    output speed without abrupt speed changes.
 *  - When the stream ends, it keeps the current/tweened velocity instead of draining faster, so the
 *    remaining buffered tail does not race in and flicker.
 */
internal class StreamingDisplayTextSmoother(
    private val initialCharsPerMs: Float = DEFAULT_CHARS_PER_MS,
) {
    private var target: String = ""
    private var revealedCount: Int = 0
    private var revealedFloat: Float = 0f
    private var lastStepMs: Long = -1L
    private var lastTargetUpdateMs: Long = -1L
    private var currentCharsPerMs: Float = initialCharsPerMs
    private var desiredCharsPerMs: Float = initialCharsPerMs
    private var streaming: Boolean = false

    /**
     * Push the latest accumulated text from the ViewModel.
     *
     * @param text full accumulated assistant text so far.
     * @param isStreaming true while the stream is still open.
     * @param nowMs current monotonic time (e.g. `System.nanoTime() / 1_000_000`).
     */
    fun updateTarget(text: String, isStreaming: Boolean, nowMs: Long) {
        if (text != target) {
            val oldTarget = target
            val rewritten = !text.startsWith(oldTarget)
            if (rewritten) {
                target = text
                revealedCount = 0
                revealedFloat = 0f
                currentCharsPerMs = initialCharsPerMs
                desiredCharsPerMs = initialCharsPerMs
                lastStepMs = nowMs
                lastTargetUpdateMs = nowMs
            } else {
                val addedChars = text.length - oldTarget.length
                val elapsedSinceTargetUpdate = if (lastTargetUpdateMs >= 0L) {
                    (nowMs - lastTargetUpdateMs).coerceAtLeast(1L)
                } else {
                    0L
                }
                target = text
                if (addedChars > 0 && elapsedSinceTargetUpdate > 0L) {
                    val incomingRate = addedChars.toFloat() / elapsedSinceTargetUpdate.toFloat()
                    desiredCharsPerMs = incomingRate.coerceIn(MIN_CHARS_PER_MS, MAX_CHARS_PER_MS)
                }
                lastTargetUpdateMs = nowMs
            }
        }
        streaming = isStreaming
        if (lastStepMs < 0L) lastStepMs = nowMs
        if (lastTargetUpdateMs < 0L) lastTargetUpdateMs = nowMs
    }

    /**
     * Advance the reveal cursor and return the substring to render.
     */
    fun step(nowMs: Long): String {
        if (target.isEmpty()) return ""

        val elapsed = if (lastStepMs >= 0L) (nowMs - lastStepMs).coerceAtLeast(0L) else 0L
        lastStepMs = nowMs

        val backlog = (target.length - revealedFloat).coerceAtLeast(0f)
        val backlogCatchupRate = if (backlog > BACKLOG_SOFT_LIMIT_CHARS) {
            ((backlog - BACKLOG_SOFT_LIMIT_CHARS) / BACKLOG_CATCHUP_WINDOW_MS)
                .coerceAtMost(MAX_BACKLOG_CATCHUP_CHARS_PER_MS)
        } else {
            0f
        }
        val targetVelocity = (desiredCharsPerMs + backlogCatchupRate)
            .coerceIn(MIN_CHARS_PER_MS, MAX_CHARS_PER_MS)

        val tweenAlpha = if (elapsed <= 0L) {
            0f
        } else {
            (elapsed.toFloat() / VELOCITY_TWEEN_MS).coerceIn(0f, 1f)
        }
        currentCharsPerMs += (targetVelocity - currentCharsPerMs) * tweenAlpha

        if (elapsed > 0L && revealedCount < target.length) {
            val advance = (elapsed * currentCharsPerMs).coerceAtLeast(MIN_ADVANCE_PER_FRAME)
            revealedFloat = (revealedFloat + advance).coerceAtMost(target.length.toFloat())
            revealedCount = revealedFloat.toInt().coerceIn(0, target.length)
            if (revealedCount == 0) revealedCount = 1
        }

        return target.substring(0, revealedCount)
    }

    /** True when the full target has been revealed. */
    val isFullyRevealed: Boolean
        get() = revealedCount >= target.length

    companion object {
        /** Initial reveal rate: ~1-2 chars per 16ms frame. */
        const val DEFAULT_CHARS_PER_MS = 0.09f

        /** Clamp to readable visual speeds even when upstream chunks arrive as large bursts. */
        private const val MIN_CHARS_PER_MS = 0.045f
        private const val MAX_CHARS_PER_MS = 0.15f

        /** Duration used to tween visible velocity toward measured upstream velocity. */
        private const val VELOCITY_TWEEN_MS = 420f

        /** Keep a modest buffer before adding catch-up pressure. */
        private const val BACKLOG_SOFT_LIMIT_CHARS = 140f
        private const val BACKLOG_CATCHUP_WINDOW_MS = 3_600f
        private const val MAX_BACKLOG_CATCHUP_CHARS_PER_MS = 0.02f
        private const val MIN_ADVANCE_PER_FRAME = 1f
    }
}
