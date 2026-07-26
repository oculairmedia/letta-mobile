package com.letta.mobile.ui.chat.render

import com.letta.mobile.data.timeline.headless.HeadlessStreamingRevealState

/**
 * Render-layer adapter around [HeadlessStreamingRevealState].
 *
 * TimelineRepository / TimelineStreamReducer remain the canonical source of truth: callers push the
 * full canonical assistant text through [updateTarget], and this class only controls how much of
 * that buffer is visible while the assistant message is actively streaming.
 */
class StreamingDisplayTextSmoother(
    private val revealCodePointsPerStep: Int = DEFAULT_REVEAL_CODE_POINTS_PER_STEP,
    private val enabled: Boolean = ENABLE_HEADLESS_STREAMING_REVEAL,
) {
    private var revealState = HeadlessStreamingRevealState(defaultRevealCodePoints = revealCodePointsPerStep)
    private var fallbackTarget: String = ""
    private var lastStepMs: Long = -1L

    /**
     * Seed the reveal cursor so the smoother starts with [prefix] already visible instead of
     * rewinding text that was painted before streaming engaged.
     */
    fun seed(prefix: String, isStreaming: Boolean, nowMs: Long) {
        if (!enabled) {
            fallbackTarget = prefix
            return
        }
        if (revealState.buffer.isNotEmpty() || prefix.isEmpty()) {
            updateTarget(prefix, isStreaming, nowMs)
            return
        }
        revealState = HeadlessStreamingRevealState(
            initialBuffer = prefix,
            initialRevealedCount = prefix.codePointCountCompat(),
            defaultRevealCodePoints = revealCodePointsPerStep,
        )
        if (!isStreaming) {
            revealState.markSourceComplete()
            revealState.skipToEnd()
        }
        lastStepMs = nowMs
    }

    /**
     * Push the latest accumulated canonical assistant text from the ViewModel.
     */
    fun updateTarget(text: String, isStreaming: Boolean, nowMs: Long) {
        if (!enabled) {
            fallbackTarget = text
            return
        }
        val currentBuffer = revealState.buffer
        when {
            text == currentBuffer -> Unit
            text.startsWith(currentBuffer) -> revealState.append(text.substring(currentBuffer.length))
            else -> revealState.replaceBuffer(text)
        }
        if (!isStreaming) {
            revealState.markSourceComplete()
            revealState.skipToEnd()
        }
        if (lastStepMs < 0L) lastStepMs = nowMs
    }

    /**
     * Advance the reveal cursor and return the substring to render.
     */
    fun step(nowMs: Long): String {
        if (!enabled) return fallbackTarget
        if (revealState.buffer.isEmpty()) return ""
        val elapsed = if (lastStepMs >= 0L) {
            (nowMs - lastStepMs).coerceAtLeast(0L)
        } else {
            STREAMING_TEXT_PAINT_INTERVAL_MS
        }
        lastStepMs = nowMs
        if (elapsed > 0L && !revealState.sourceComplete) {
            val steps = (elapsed / STREAMING_TEXT_PAINT_INTERVAL_MS)
                .coerceAtLeast(1L)
                .coerceAtMost(MAX_REVEAL_STEPS_PER_FRAME)
            repeat(steps.toInt()) { revealState.revealNext() }
        }
        return revealState.revealed
    }

    /** True when the full target has been revealed. */
    val isFullyRevealed: Boolean
        get() = if (enabled) revealState.revealed.length >= revealState.buffer.length else true

    companion object {
        private const val DEFAULT_REVEAL_CODE_POINTS_PER_STEP = 8
        private const val MAX_REVEAL_STEPS_PER_FRAME = 4L
    }
}

const val ENABLE_HEADLESS_STREAMING_REVEAL = true

private fun String.codePointCountCompat(): Int {
    var count = 0
    var index = 0
    while (index < length) {
        index += if (this[index].isHighSurrogateCompat() && index + 1 < length && this[index + 1].isLowSurrogateCompat()) {
            2
        } else {
            1
        }
        count += 1
    }
    return count
}

private fun Char.isHighSurrogateCompat(): Boolean = this in '\uD800'..'\uDBFF'

private fun Char.isLowSurrogateCompat(): Boolean = this in '\uDC00'..'\uDFFF'
