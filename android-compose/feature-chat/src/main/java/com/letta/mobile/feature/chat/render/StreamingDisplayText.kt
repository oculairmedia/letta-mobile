package com.letta.mobile.feature.chat.render

import androidx.annotation.VisibleForTesting

internal const val STREAMING_CURSOR = "\u258E" // ▎ LEFT VERTICAL BAR

@VisibleForTesting
internal const val MAX_HELD_TAIL_CHARS = 24

/**
 * letta-mobile-6p4o.1 — perceived garbling fix (A + C), with live
 * markdown-tail repair layered on top.
 *
 * See StreamingDisplayTextTest for exhaustive contract verification.
 */
@VisibleForTesting
internal fun streamingDisplayText(raw: String): String {
    if (raw.isEmpty()) return ""
    if (insideOpenCodeFence(raw)) return raw
    if (hasOpenDisplayMathFence(raw)) return raw
    return raw
}

@VisibleForTesting
internal fun clampToWordBoundary(raw: String): String {
    if (raw.isEmpty()) return raw
    if (raw.last().isStreamingBoundary()) return raw
    val boundary = raw.indexOfLast { it.isStreamingBoundary() }
    if (boundary < 0) return raw
    if (raw.length - boundary - 1 > MAX_HELD_TAIL_CHARS) return raw
    return raw.substring(0, boundary + 1)
}

@VisibleForTesting
internal val STREAMING_BOUNDARY_CHARS =
    setOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '—', '-', '/', '\\')

@VisibleForTesting
internal fun Char.isStreamingBoundary(): Boolean =
    isWhitespace() || this in STREAMING_BOUNDARY_CHARS

/**
 * Whether a streaming cursor glyph is appropriate for the supplied raw
 * text. Returns false for empty text and inside open ``` code fences.
 */
internal fun shouldShowStreamingCursor(raw: String): Boolean =
    raw.isNotEmpty() && !insideOpenCodeFence(raw)

@VisibleForTesting
internal fun shouldPulseForStreamingReveal(
    previousLength: Int,
    revealedText: String,
): Boolean {
    val revealedLength = revealedText.length
    if (revealedLength <= previousLength) return false
    if (revealedLength - previousLength >= STREAMING_REVEAL_HAPTIC_MIN_CHARS) return true
    val lastChar = revealedText.lastOrNull() ?: return false
    return lastChar.isWhitespace() || lastChar in STREAMING_REVEAL_HAPTIC_BOUNDARY_CHARS
}

private const val STREAMING_REVEAL_HAPTIC_MIN_CHARS = 10
private const val STREAMING_REVEAL_HAPTIC_BOUNDARY_CHARS = ".,;:!?)]}"
