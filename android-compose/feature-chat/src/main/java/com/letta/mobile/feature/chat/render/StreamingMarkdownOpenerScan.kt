package com.letta.mobile.feature.chat.render

import androidx.annotation.VisibleForTesting

private sealed interface OpenerScanStep {
    data class Continue(val nextIndex: Int) : OpenerScanStep
    data class Unmatched(val index: Int) : OpenerScanStep
}

internal data class MarkdownOpenerScanLine(val raw: String) {
    val length: Int get() = raw.length
    operator fun get(index: Int): Char = raw[index]
}

/**
 * Returns the index (relative to [line]) of the FIRST unmatched
 * span opener — the position where a closer hasn't yet arrived.
 * Returns -1 if the line is fully balanced.
 */
@VisibleForTesting
internal fun findUnmatchedOpenerInLine(line: MarkdownOpenerScanLine): Int {
    var i = 0
    val len = line.length
    while (i < len) {
        when (val step = scanOpenerStep(line, i, len)) {
            is OpenerScanStep.Unmatched -> return step.index
            is OpenerScanStep.Continue -> i = step.nextIndex
        }
    }
    return -1
}

private fun scanOpenerStep(line: MarkdownOpenerScanLine, index: Int, len: Int): OpenerScanStep =
    when (line[index]) {
        '`' -> scanBacktickOpener(line, index)
        '*', '_' -> scanEmphasisOpener(line, index, len, line[index])
        '~' -> scanStrikethroughOpener(line, index, len)
        '[' -> scanLinkOpener(line, index, len)
        else -> OpenerScanStep.Continue(index + 1)
    }

private fun scanBacktickOpener(line: MarkdownOpenerScanLine, index: Int): OpenerScanStep {
    val close = line.raw.indexOf('`', startIndex = index + 1)
    if (close < 0) return OpenerScanStep.Unmatched(index)
    return OpenerScanStep.Continue(close + 1)
}

private fun scanEmphasisOpener(
    line: MarkdownOpenerScanLine,
    index: Int,
    len: Int,
    marker: Char,
): OpenerScanStep {
    var run = 1
    while (index + run < len && line[index + run] == marker) run++
    val closerIdx = findEmphasisCloser(line, index + run, marker, run)
    if (closerIdx < 0) return OpenerScanStep.Unmatched(index)
    return OpenerScanStep.Continue(closerIdx + run)
}

private fun scanStrikethroughOpener(line: MarkdownOpenerScanLine, index: Int, len: Int): OpenerScanStep {
    if (index + 1 >= len) return OpenerScanStep.Continue(index + 1)
    if (line[index + 1] != '~') return OpenerScanStep.Continue(index + 1)
    val close = line.raw.indexOf("~~", startIndex = index + 2)
    if (close < 0) return OpenerScanStep.Unmatched(index)
    return OpenerScanStep.Continue(close + 2)
}

private fun scanLinkOpener(line: MarkdownOpenerScanLine, index: Int, len: Int): OpenerScanStep {
    val closeBracket = line.raw.indexOf(']', startIndex = index + 1)
    if (closeBracket < 0) return OpenerScanStep.Unmatched(index)
    if (!hasLinkOpenParen(line, closeBracket, len)) {
        return OpenerScanStep.Unmatched(index)
    }
    val closeParen = line.raw.indexOf(')', startIndex = closeBracket + 2)
    if (closeParen < 0) return OpenerScanStep.Unmatched(index)
    return OpenerScanStep.Continue(closeParen + 1)
}

private fun hasLinkOpenParen(line: MarkdownOpenerScanLine, closeBracket: Int, len: Int): Boolean {
    val parenIndex = closeBracket + 1
    if (parenIndex >= len) return false
    return line[parenIndex] == '('
}

private fun findEmphasisCloser(line: MarkdownOpenerScanLine, from: Int, marker: Char, runLen: Int): Int {
    var i = from
    val len = line.length
    while (i < len) {
        if (line[i] != marker) {
            i++
            continue
        }
        var run = 1
        while (i + run < len && line[i + run] == marker) run++
        if (run >= runLen) return i
        i += run
    }
    return -1
}
