package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.letta.mobile.ui.chat.voice.VoiceSpokenSegment
import kotlin.math.roundToInt

/** The reply as the card shows it: its pieces in order, trimmed, one space between them. */
internal fun spokenReply(segments: List<VoiceSpokenSegment>): String =
    segments.sortedBy { it.index }.joinToString(SEPARATOR) { it.text.trim() }

/**
 * How many characters of [spokenReply] have been spoken at [nowMs]. A finished piece counts whole; so
 * do the pieces before it. The piece playing counts in proportion to how far its audio has played
 * (characters stand in for words: no word timings exist), or whole when [interpolate] is false
 * (reduced motion: the light moves a piece at a time) or its audio's length is not known yet. A piece
 * not yet playing, and everything after it, counts nothing.
 */
internal fun spokenCharacters(segments: List<VoiceSpokenSegment>, nowMs: Long, interpolate: Boolean): Int {
    var spoken = 0
    segments.sortedBy { it.index }.forEachIndexed { position, segment ->
        val length = segment.text.trim().length + if (position > 0) SEPARATOR.length else 0
        val started = segment.startedAtMs ?: return spoken
        if (segment.finishedAtMs != null) {
            spoken += length
            return@forEachIndexed
        }
        return spoken + (length * playedShare(segment, started, nowMs, interpolate)).roundToInt()
    }
    return spoken
}

/** How much of a playing [segment]'s audio has played: all of it when it is not interpolated. */
private fun playedShare(segment: VoiceSpokenSegment, started: Long, nowMs: Long, interpolate: Boolean): Float {
    val duration = segment.audioDurationMs?.takeIf { it > 0 } ?: return 1f
    if (!interpolate) return 1f
    return ((nowMs - started).toFloat() / duration).coerceIn(0f, 1f)
}

/** [text] with its first [spoken] characters in [bright] and the rest in [dim]. */
internal fun spokenAnnotated(text: String, spoken: Int, bright: Color, dim: Color): AnnotatedString {
    val split = spoken.coerceIn(0, text.length)
    return buildAnnotatedString {
        withStyle(SpanStyle(color = bright)) { append(text, 0, split) }
        withStyle(SpanStyle(color = dim)) { append(text, split, text.length) }
    }
}

private const val SEPARATOR = " "
