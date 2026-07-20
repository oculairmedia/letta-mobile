package com.letta.mobile.feature.chat.render

import androidx.annotation.VisibleForTesting

private const val MAX_SIMPLE_INLINE_MATH_CHARS = 3

@JvmInline
internal value class MarkdownMathScanLine(val raw: String)

@VisibleForTesting
internal fun findUnmatchedMathOpenerInLine(line: MarkdownMathScanLine): Int {
    val displayOpenIdx = findUnmatchedDisplayMathOpenerInLine(line)
    val inlineOpenIdx = findUnmatchedInlineMathOpenerInLine(line)
    return when {
        displayOpenIdx < 0 -> inlineOpenIdx
        inlineOpenIdx < 0 -> displayOpenIdx
        else -> minOf(displayOpenIdx, inlineOpenIdx)
    }
}

private fun findUnmatchedDisplayMathOpenerInLine(line: MarkdownMathScanLine): Int {
    var openIdx = -1
    var i = 0
    while (i <= line.raw.length - 2) {
        if (line.raw[i] == '\\') {
            i += 2
            continue
        }
        if (!isDisplayMathDelimiterAt(line, i)) {
            i++
            continue
        }
        openIdx = if (openIdx >= 0) -1 else i
        i += 2
    }
    return openIdx
}

private fun isDisplayMathDelimiterAt(line: MarkdownMathScanLine, index: Int): Boolean =
    line.raw[index] == '$' && line.raw[index + 1] == '$'

private fun findUnmatchedInlineMathOpenerInLine(line: MarkdownMathScanLine): Int {
    val opener = findLastInlineMathOpener(line)
    if (opener < 0) return -1
    val body = line.raw.substring(opener + 1)
    return if (isLikelyIncompleteInlineMathBody(body)) opener else -1
}

private fun findLastInlineMathOpener(line: MarkdownMathScanLine): Int {
    var opener = -1
    var i = 0
    while (i < line.raw.length) {
        if (line.raw.isSingleDollarAt(i) && !line.raw.isInsideInlineCodeAt(i)) {
            opener = resolveInlineMathOpener(line, i, opener)
        }
        i++
    }
    return opener
}

private fun resolveInlineMathOpener(line: MarkdownMathScanLine, index: Int, currentOpener: Int): Int {
    if (closesPriorInlineMathOpener(line, index, currentOpener)) return -1
    return if (canOpenInlineMathAt(line, index)) index else currentOpener
}

private fun closesPriorInlineMathOpener(line: MarkdownMathScanLine, index: Int, currentOpener: Int): Boolean {
    if (currentOpener < 0) return false
    if (index <= 0) return false
    return !line.raw[index - 1].isWhitespace()
}

private fun canOpenInlineMathAt(line: MarkdownMathScanLine, index: Int): Boolean {
    val next = index + 1
    if (next >= line.raw.length) return false
    if (line.raw[next].isWhitespace()) return false
    if (line.raw[next].isDigit()) return false
    if (index > 0 && line.raw[index - 1].isWordChar()) return false
    return true
}

private fun isLikelyIncompleteInlineMathBody(body: String): Boolean {
    if (!hasNonBlankNonWhitespaceTail(body)) return false
    if (bodyContainsMathTerminator(body)) return false
    if (bodyContainsMathSyntax(body)) return true
    if (isShellLikeVariable(body)) return false
    return isSimpleInlineMathToken(body)
}

private fun hasNonBlankNonWhitespaceTail(body: String): Boolean {
    if (body.isBlank()) return false
    return !body.last().isWhitespace()
}

private fun bodyContainsMathTerminator(body: String): Boolean {
    for (ch in body) {
        if (ch == '$' || ch == '\n') return true
    }
    return false
}

private fun bodyContainsMathSyntax(body: String): Boolean {
    for (ch in body) {
        if (ch in "\\^_{}=+-*/<>(),[]") return true
    }
    return false
}

private fun isShellLikeVariable(body: String): Boolean =
    body.all { it.isUpperCase() || it.isDigit() || it == '_' }

private fun isSimpleInlineMathToken(body: String): Boolean {
    if (body.length > MAX_SIMPLE_INLINE_MATH_CHARS) return false
    if (!body.all { it.isLetter() }) return false
    if (body.length == 1) return true
    return body.any { it.isLowerCase() }
}
