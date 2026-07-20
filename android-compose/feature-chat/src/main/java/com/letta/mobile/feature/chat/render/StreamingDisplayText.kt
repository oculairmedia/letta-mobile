package com.letta.mobile.feature.chat.render

import androidx.annotation.VisibleForTesting

internal const val STREAMING_CURSOR = "\u258E" // ▎ LEFT VERTICAL BAR

@VisibleForTesting
internal const val MAX_HELD_TAIL_CHARS = 24

/**
 * letta-mobile-6p4o.1 — perceived garbling fix (A + C), with live
 * markdown-tail repair layered on top.
 *
 * Background: lettabot (and Letta direct) emit assistant deltas at
 * arbitrary character boundaries — frequently mid-word. Wire-level
 * trace from `:cli wsstream` shows splits like
 *   frame N:   "...suspension is cheap, no O"
 *   frame N+1: "S thread per coroutine\n- ..."
 * The merge math is correct, but the user briefly sees `"...no O"` on
 * screen between recompositions. That's the "garbled text" reported in
 * letta-mobile-6p4o.
 *
 * Fix:
 *  - **A**: while streaming, render every received character. Earlier
 *    revisions hid trailing partial words, but that made acronyms and
 *    short tokens appear to drop letters on-device (for example `A2UI`
 *    rendering as `AUI` while streaming).
 *  - **Markdown repair**: when the tail is inside an incomplete markdown
 *    construct, pass the raw tail through so the design-system renderer can
 *    close it synthetically for display instead of hiding it.
 *  - **C**: append a thin vertical bar (`▎`) at the cursor position so
 *    partial text reads as "in progress" rather than "broken".
 *
 * Long-tail safety:
 *  - Plain prose is no longer word-boundary clamped; rendering all received
 *    characters is safer than hiding suffixes on narrow mobile frames.
 *  - Inside an unclosed fenced code block we skip the cursor (it'd be
 *    rendered as literal text inside the code) and skip the clamp
 *    (whitespace inside code blocks is meaningful and we don't want to
 *    swallow it).
 */
@VisibleForTesting
internal fun streamingDisplayText(raw: String): String {
    // letta-mobile-flk2 (revision 11+): markdown-tail handoff.
    //
    // Background: mikepenz's renderer parses the full text on every
    // re-emission. While streaming, the latest tail almost always
    // contains an INCOMPLETE markdown construct — an open `**`, an
    // open `` ` ``, an open `[link`, etc. The parser refuses to
    // commit partial syntax: it falls back to rendering those bytes
    // as literal text. A few ms (one paint tick) later the closer
    // arrives in the next chunk and the parser re-parses, this time
    // emitting the formatted markup. The user sees: raw asterisks
    // and brackets briefly, then they "snap in" to bold/italic/link.
    // Emmanuel reported this as "flashing the content that streamed
    // in but hasn't been markdownified yet".
    //
    // Current mitigation: let obvious markdown tails through unchanged. The
    // design-system streaming renderer repairs that tail into syntactically
    // valid markdown for display only, which preserves live formatting without
    // leaking raw delimiters. Plain prose also renders as-received; the cursor
    // communicates that the tail is still in progress.
    //
    // Inside an open ``` fence we skip the clamp entirely (whitespace
    // is meaningful in code, and the parser already renders the
    // partial fence as a code block — no flicker).
    //
    // letta-mobile-d9zy.5: cursor injection moved out of this function.
    // [StreamingMarkdownText] now owns cursor rendering so the cursor
    // glyph can fade independently via cursorAlpha. Empty input still
    // returns empty so the renderer short-circuits to no render at all
    // (the typing indicator already covers the pre-content state).
    //
    // ── letta-mobile-644ez: prefix-stability contract ──
    //
    // This function implements a prefix-stable streaming markdown pipeline
    // (inspired by llm-typewriter). The guarantees:
    //
    //  1. No text is ever dropped — incomplete markup renders as plain
    //     text until its closer arrives.
    //  2. Earlier prefixes are stable — committed markdown never flickers
    //     when new chunks arrive.
    //  3. Incomplete tail markup (open **, *, _, __, `, ~~, [) passes
    //     through unclamped — the renderer repairs it.
    //  4. Fenced code/math block transitions are clean — open fence content
    //     passes through unchanged.
    //  5. Plain prose renders as-received; the streaming cursor communicates
    //     partialness without hiding already-arrived characters.
    //
    // See StreamingDisplayTextTest for exhaustive contract verification.
    if (raw.isEmpty()) return ""
    if (insideOpenCodeFence(raw)) {
        return raw
    }
    if (hasOpenDisplayMathFence(raw)) {
        return raw
    }
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

// perf/frame-budget-audit: hoist the boundary set to a module-level constant.
// isStreamingBoundary() is called per-character of the streaming tail (via
// indexOfLast in clampToWordBoundary) on every streamingDisplayText pass, so a
// `setOf(...)` literal inside the function allocated a fresh Set per char —
// pure GC churn on the streaming hot path.
@VisibleForTesting
internal val STREAMING_BOUNDARY_CHARS =
    setOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '—', '-', '/', '\\')

@VisibleForTesting
internal fun Char.isStreamingBoundary(): Boolean =
    isWhitespace() || this in STREAMING_BOUNDARY_CHARS

/**
 * Whether a streaming cursor glyph is appropriate for the supplied raw
 * text. Returns false for empty text (would flash a lone cursor before
 * any chunks arrive) and inside open ``` code fences (would render as
 * literal content inside the code block).
 *
 * Companion to [streamingDisplayText]; callers pair the two so the
 * markdown-stability clamp and cursor-eligibility decision agree about
 * the in-fence carve-out.
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

internal fun String.containsMarkdownTable(): Boolean {
    var previousStart = -1
    var previousEnd = -1
    var lineStart = 0

    while (lineStart <= length) {
        val newlineIndex = indexOf('\n', startIndex = lineStart)
        val rawEnd = if (newlineIndex >= 0) newlineIndex else length
        val contentStart = firstNonWhitespaceIndex(lineStart, rawEnd)
        if (contentStart < rawEnd) {
            val contentEnd = lastNonWhitespaceExclusive(contentStart, rawEnd)
            if (previousStart >= 0 &&
                lineContainsPipe(previousStart, previousEnd) &&
                looksLikeMarkdownTableSeparator(contentStart, contentEnd)
            ) {
                return true
            }
            previousStart = contentStart
            previousEnd = contentEnd
        }
        if (newlineIndex < 0) break
        lineStart = newlineIndex + 1
    }
    return false
}

private fun String.firstNonWhitespaceIndex(start: Int, endExclusive: Int): Int {
    var index = start
    while (index < endExclusive && this[index].isWhitespace()) {
        index += 1
    }
    return index
}

private fun String.lastNonWhitespaceExclusive(start: Int, endExclusive: Int): Int {
    var index = endExclusive
    while (index > start && this[index - 1].isWhitespace()) {
        index -= 1
    }
    return index
}

private fun String.lineContainsPipe(start: Int, endExclusive: Int): Boolean {
    for (index in start until endExclusive) {
        if (this[index] == '|') return true
    }
    return false
}

private fun String.looksLikeMarkdownTableSeparator(start: Int, endExclusive: Int): Boolean {
    var cellHasDash = false
    var foundCell = false
    var index = start

    while (index < endExclusive) {
        when (this[index]) {
            '|', ' ', '\t' -> {
                if (this[index] == '|' && cellHasDash) {
                    foundCell = true
                    cellHasDash = false
                }
            }
            ':' -> Unit
            '-' -> cellHasDash = true
            else -> return false
        }
        index += 1
    }
    return foundCell || cellHasDash
}

/** True if the text contains an odd number of ``` fences (i.e. a code block is currently open). */
@VisibleForTesting
internal fun insideOpenCodeFence(text: String): Boolean {
    var count = 0
    var i = 0
    while (i <= text.length - 3) {
        if (text[i] == '`' && text[i + 1] == '`' && text[i + 2] == '`') {
            count++
            i += 3
        } else {
            i++
        }
    }
    return count % 2 == 1
}

@VisibleForTesting
internal fun hasOpenDisplayMathFence(text: String): Boolean {
    var open = false
    var i = 0
    while (i <= text.length - 2) {
        if (text[i] == '\\') {
            i += 2
            continue
        }
        if (!text.isInsideInlineCodeAt(i) && text[i] == '$' && text[i + 1] == '$') {
            open = !open
            i += 2
            continue
        }
        i++
    }
    return open
}

internal fun String.isSingleDollarAt(index: Int): Boolean {
    if (this[index] != '$' || isEscapedAt(index)) return false
    if (index > 0 && this[index - 1] == '$') return false
    if (index + 1 < length && this[index + 1] == '$') return false
    return true
}

internal fun String.isEscapedAt(index: Int): Boolean {
    var slashCount = 0
    var i = index - 1
    while (i >= 0 && this[i] == '\\') {
        slashCount++
        i--
    }
    return slashCount % 2 == 1
}

internal fun String.isInsideInlineCodeAt(index: Int): Boolean {
    var open = false
    var i = 0
    while (i < index) {
        if (this[i] == '`' && !isEscapedAt(i)) {
            open = !open
        }
        i++
    }
    return open
}

internal fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '_'
