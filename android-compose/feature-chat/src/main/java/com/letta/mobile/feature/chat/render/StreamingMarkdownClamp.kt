package com.letta.mobile.feature.chat.render

import androidx.annotation.VisibleForTesting

private const val MAX_SIMPLE_INLINE_MATH_CHARS = 3

/**
 * Returns the longest prefix of [raw] that does NOT end inside an
 * open markdown construct. The returned prefix is what the renderer
 * can safely re-parse without producing a "raw markup flash" when
 * the construct's closer arrives in a later chunk.
 *
 * Constructs we hold back:
 *  - Trailing `*`/`**`/`***` with no matching closer in the
 *    *current line* (could be opening italic/bold).
 *  - Trailing `_`/`__` with no matching closer in the current line.
 *  - Trailing `` ` `` with no matching closer in the current line.
 *  - Trailing `~~` with no matching closer.
 *  - Trailing `[...]` link text without a `(url)` yet.
 *  - Trailing partial autolink/URL not yet ended by space/newline.
 *
 * The clamp scans only the LAST line — most markdown constructs
 * (emphasis, code spans, links) close on the same line, so we don't
 * need to look back further. Block constructs (lists, headings,
 * paragraphs) are stable as soon as their content tokens land; the
 * parser handles those incrementally without flashing.
 */
@VisibleForTesting
internal fun clampToStableMarkdown(raw: String): String {
    val lastBreak = raw.lastIndexOf('\n')
    val lineStart = if (lastBreak < 0) 0 else lastBreak + 1
    val line = raw.substring(lineStart)
    if (line.isEmpty()) return raw

    val unmatchedMathOpenIdx = findUnmatchedMathOpenerInLine(line)
    if (unmatchedMathOpenIdx >= 0) {
        return raw.substring(0, lineStart + unmatchedMathOpenIdx)
    }

    // Scan for unmatched span openers in this line. Track whether the
    // last unmatched opener exists; if so, return raw clipped to BEFORE
    // that opener.
    val unmatchedOpenIdx = findUnmatchedOpenerInLine(line)
    if (unmatchedOpenIdx >= 0) {
        return raw.substring(0, lineStart + unmatchedOpenIdx)
    }
    return raw
}

@VisibleForTesting
internal fun findUnmatchedMathOpenerInLine(line: String): Int {
    val displayOpenIdx = findUnmatchedDisplayMathOpenerInLine(line)
    val inlineOpenIdx = findUnmatchedInlineMathOpenerInLine(line)
    return when {
        displayOpenIdx < 0 -> inlineOpenIdx
        inlineOpenIdx < 0 -> displayOpenIdx
        else -> minOf(displayOpenIdx, inlineOpenIdx)
    }
}

private fun findUnmatchedDisplayMathOpenerInLine(line: String): Int {
    var openIdx = -1
    var i = 0
    while (i <= line.length - 2) {
        if (line[i] == '\\') {
            i += 2
            continue
        }
        if (line[i] == '$' && line[i + 1] == '$') {
            openIdx = if (openIdx >= 0) -1 else i
            i += 2
            continue
        }
        i++
    }
    return openIdx
}

private fun findUnmatchedInlineMathOpenerInLine(line: String): Int {
    var opener = -1
    var i = 0
    while (i < line.length) {
        if (line.isSingleDollarAt(i) && !line.isInsideInlineCodeAt(i)) {
            when {
                opener >= 0 && i > 0 && !line[i - 1].isWhitespace() -> opener = -1
                canOpenInlineMathAt(line, i) -> opener = i
            }
        }
        i++
    }
    if (opener < 0) return -1

    val body = line.substring(opener + 1)
    return if (isLikelyIncompleteInlineMathBody(body)) opener else -1
}

private fun canOpenInlineMathAt(line: String, index: Int): Boolean {
    val next = index + 1
    if (next >= line.length) return false
    if (line[next].isWhitespace() || line[next].isDigit()) return false
    if (index > 0 && line[index - 1].isWordChar()) return false
    return true
}

private fun isLikelyIncompleteInlineMathBody(body: String): Boolean {
    if (body.isBlank() || body.last().isWhitespace()) return false
    if (body.any { it == '$' || it == '\n' }) return false

    val hasMathSyntax = body.any { it in "\\^_{}=+-*/<>(),[]" }
    if (hasMathSyntax) return true

    val isShellLikeVariable = body.all { it.isUpperCase() || it.isDigit() || it == '_' }
    if (isShellLikeVariable) return false

    return body.length <= MAX_SIMPLE_INLINE_MATH_CHARS &&
        body.all { it.isLetter() } &&
        (body.length == 1 || body.any { it.isLowerCase() })
}

/**
 * Returns the index (relative to [line]) of the FIRST unmatched
 * span opener — the position where a closer hasn't yet arrived.
 * Returns -1 if the line is fully balanced.
 *
 * Tokens checked: `***`, `**`, `*`, `___`, `__`, `_`, `` `` ``,
 * `` ` ``, `~~`, `[`.
 *
 * Implementation note: we use a simple stack-based scanner. When we
 * see an opener whose matching closer isn't found later in the line,
 * we record its index. If multiple unmatched openers exist we return
 * the EARLIEST one, since clipping there hides all of them.
 */
@VisibleForTesting
internal fun findUnmatchedOpenerInLine(line: String): Int {
    var earliestUnmatched = -1
    var i = 0
    val len = line.length
    while (i < len) {
        val c = line[i]
        when (c) {
            '`' -> {
                // Inline code: find matching ` (single backtick) or
                // ``...`` style. We only handle single-backtick spans
                // here — multi-backtick is rare in prose.
                val close = line.indexOf('`', startIndex = i + 1)
                if (close < 0) {
                    if (earliestUnmatched < 0) earliestUnmatched = i
                    break
                }
                i = close + 1
            }
            '*', '_' -> {
                // Count run length
                var run = 1
                while (i + run < len && line[i + run] == c) run++
                // Look for a matching closer of run length later in the line.
                // For simplicity treat any later run of >= our length as a closer.
                val searchFrom = i + run
                val closerIdx = findEmphasisCloser(line, searchFrom, c, run)
                if (closerIdx < 0) {
                    if (earliestUnmatched < 0) earliestUnmatched = i
                    // Stop scanning — anything after is moot since we
                    // already need to clip here.
                    break
                }
                i = closerIdx + run
            }
            '~' -> {
                if (i + 1 < len && line[i + 1] == '~') {
                    val close = line.indexOf("~~", startIndex = i + 2)
                    if (close < 0) {
                        if (earliestUnmatched < 0) earliestUnmatched = i
                        break
                    }
                    i = close + 2
                } else {
                    i++
                }
            }
            '[' -> {
                // Look for closing `]` then `(...)` in the same line.
                val closeBracket = line.indexOf(']', startIndex = i + 1)
                if (closeBracket < 0 || closeBracket + 1 >= len ||
                    line[closeBracket + 1] != '(') {
                    if (earliestUnmatched < 0) earliestUnmatched = i
                    break
                }
                val closeParen = line.indexOf(')', startIndex = closeBracket + 2)
                if (closeParen < 0) {
                    if (earliestUnmatched < 0) earliestUnmatched = i
                    break
                }
                i = closeParen + 1
            }
            else -> i++
        }
    }
    return earliestUnmatched
}

private fun findEmphasisCloser(line: String, from: Int, marker: Char, runLen: Int): Int {
    var i = from
    val len = line.length
    while (i < len) {
        if (line[i] == marker) {
            var run = 1
            while (i + run < len && line[i + run] == marker) run++
            if (run >= runLen) return i
            i += run
        } else {
            i++
        }
    }
    return -1
}
