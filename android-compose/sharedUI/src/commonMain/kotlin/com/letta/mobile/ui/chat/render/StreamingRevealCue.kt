package com.letta.mobile.ui.chat.render

/**
 * letta-mobile-bglj6.1.18: the reveal-pulse gate, ported from feature-chat's
 * StreamingDisplayText.shouldPulseForStreamingReveal (the legacy Android chat's per-reveal
 * haptic gate): a reveal step pulses when it crosses a chunk of new characters or lands on a
 * word/sentence boundary. The cue itself is rate-limited by its own floor (StreamingPulse,
 * 96ms) on top of this gate.
 */
internal const val STREAMING_REVEAL_CUE_MIN_CHARS = 10

private val STREAMING_REVEAL_CUE_BOUNDARY_CHARS = ".,;:!?)]}"

internal fun shouldPulseForStreamingReveal(previousLength: Int, revealedText: String): Boolean {
    val revealedLength = revealedText.length
    if (revealedLength <= previousLength) return false
    if (revealedLength - previousLength >= STREAMING_REVEAL_CUE_MIN_CHARS) return true
    val lastChar = revealedText.lastOrNull() ?: return false
    return lastChar.isWhitespace() || lastChar in STREAMING_REVEAL_CUE_BOUNDARY_CHARS
}