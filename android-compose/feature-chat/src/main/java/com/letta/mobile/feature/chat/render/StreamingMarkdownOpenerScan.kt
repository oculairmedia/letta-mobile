package com.letta.mobile.feature.chat.render

private sealed interface OpenerScanStep {
    data class Continue(val nextIndex: MarkdownOpenerScanIndex) : OpenerScanStep
    data class Unmatched(val index: MarkdownOpenerScanIndex) : OpenerScanStep
}

internal data class MarkdownOpenerScanLine(val raw: String) {
    val length: MarkdownOpenerScanIndex get() = MarkdownOpenerScanIndex(raw.length)
    operator fun get(index: MarkdownOpenerScanIndex): Char = raw[index.value]
}

internal data class MarkdownOpenerScanIndex(val value: Int)

internal data class MarkdownEmphasisMarker(val value: Char)

internal data class MarkdownOpenerScanCursor(
    val line: MarkdownOpenerScanLine,
    val index: MarkdownOpenerScanIndex,
)

internal data class MarkdownOpenerScanBounds(
    val line: MarkdownOpenerScanLine,
    val start: MarkdownOpenerScanIndex,
    val end: MarkdownOpenerScanIndex,
)

/**
 * Returns the index (relative to [line]) of the FIRST unmatched
 * span opener — the position where a closer hasn't yet arrived.
 * Returns -1 if the line is fully balanced.
 */
internal fun findUnmatchedOpenerInLine(line: MarkdownOpenerScanLine): Int {
    var cursor = MarkdownOpenerScanCursor(line, MarkdownOpenerScanIndex(0))
    val end = line.length
    while (cursor.index.value < end.value) {
        when (val step = scanOpenerStep(cursor, end)) {
            is OpenerScanStep.Unmatched -> return step.index.value
            is OpenerScanStep.Continue -> cursor = cursor.at(step.nextIndex)
        }
    }
    return -1
}

private fun scanOpenerStep(
    cursor: MarkdownOpenerScanCursor,
    end: MarkdownOpenerScanIndex,
): OpenerScanStep =
    when (cursor.line[cursor.index]) {
        '`' -> scanBacktickOpener(cursor)
        '*', '_' -> scanEmphasisOpener(cursor, end, MarkdownEmphasisMarker(cursor.line[cursor.index]))
        '~' -> scanStrikethroughOpener(cursor, end)
        '[' -> scanLinkOpener(cursor, end)
        else -> OpenerScanStep.Continue(MarkdownOpenerScanIndex(cursor.index.value + 1))
    }

private fun scanBacktickOpener(cursor: MarkdownOpenerScanCursor): OpenerScanStep {
    val close = cursor.line.raw.indexOf('`', startIndex = cursor.index.value + 1)
    if (close < 0) return OpenerScanStep.Unmatched(cursor.index)
    return OpenerScanStep.Continue(MarkdownOpenerScanIndex(close + 1))
}

private fun scanEmphasisOpener(
    cursor: MarkdownOpenerScanCursor,
    end: MarkdownOpenerScanIndex,
    marker: MarkdownEmphasisMarker,
): OpenerScanStep {
    var run = 1
    while (cursor.index.value + run < end.value && cursor.line[MarkdownOpenerScanIndex(cursor.index.value + run)] == marker.value) {
        run++
    }
    val closerIdx = findEmphasisCloser(
        MarkdownOpenerScanBounds(
            line = cursor.line,
            start = MarkdownOpenerScanIndex(cursor.index.value + run),
            end = end,
        ),
        marker = marker,
        runLen = run,
    )
    if (closerIdx.value < 0) return OpenerScanStep.Unmatched(cursor.index)
    return OpenerScanStep.Continue(MarkdownOpenerScanIndex(closerIdx.value + run))
}

private fun scanStrikethroughOpener(
    cursor: MarkdownOpenerScanCursor,
    end: MarkdownOpenerScanIndex,
): OpenerScanStep {
    if (cursor.index.value + 1 >= end.value) {
        return OpenerScanStep.Continue(MarkdownOpenerScanIndex(cursor.index.value + 1))
    }
    if (cursor.line[MarkdownOpenerScanIndex(cursor.index.value + 1)] != '~') {
        return OpenerScanStep.Continue(MarkdownOpenerScanIndex(cursor.index.value + 1))
    }
    val close = cursor.line.raw.indexOf("~~", startIndex = cursor.index.value + 2)
    if (close < 0) return OpenerScanStep.Unmatched(cursor.index)
    return OpenerScanStep.Continue(MarkdownOpenerScanIndex(close + 2))
}

private fun scanLinkOpener(
    cursor: MarkdownOpenerScanCursor,
    end: MarkdownOpenerScanIndex,
): OpenerScanStep {
    val closeBracket = cursor.line.raw.indexOf(']', startIndex = cursor.index.value + 1)
    if (closeBracket < 0) return OpenerScanStep.Unmatched(cursor.index)
    val closeBracketIndex = MarkdownOpenerScanIndex(closeBracket)
    if (!hasLinkOpenParen(cursor.line, closeBracketIndex, end)) {
        return OpenerScanStep.Unmatched(cursor.index)
    }
    val closeParen = cursor.line.raw.indexOf(')', startIndex = closeBracket + 2)
    if (closeParen < 0) return OpenerScanStep.Unmatched(cursor.index)
    return OpenerScanStep.Continue(MarkdownOpenerScanIndex(closeParen + 1))
}

private fun hasLinkOpenParen(
    line: MarkdownOpenerScanLine,
    closeBracket: MarkdownOpenerScanIndex,
    end: MarkdownOpenerScanIndex,
): Boolean {
    val parenIndex = MarkdownOpenerScanIndex(closeBracket.value + 1)
    if (parenIndex.value >= end.value) return false
    return line[parenIndex] == '('
}

private fun findEmphasisCloser(
    bounds: MarkdownOpenerScanBounds,
    marker: MarkdownEmphasisMarker,
    runLen: Int,
): MarkdownOpenerScanIndex {
    var cursor = MarkdownOpenerScanCursor(bounds.line, bounds.start)
    while (cursor.index.value < bounds.end.value) {
        if (cursor.line[cursor.index] != marker.value) {
            cursor = cursor.at(MarkdownOpenerScanIndex(cursor.index.value + 1))
            continue
        }
        var run = 1
        while (cursor.index.value + run < bounds.end.value &&
            cursor.line[MarkdownOpenerScanIndex(cursor.index.value + run)] == marker.value
        ) {
            run++
        }
        if (run >= runLen) return cursor.index
        cursor = cursor.at(MarkdownOpenerScanIndex(cursor.index.value + run))
    }
    return MarkdownOpenerScanIndex(-1)
}

private fun MarkdownOpenerScanCursor.at(index: MarkdownOpenerScanIndex): MarkdownOpenerScanCursor =
    copy(index = index)
