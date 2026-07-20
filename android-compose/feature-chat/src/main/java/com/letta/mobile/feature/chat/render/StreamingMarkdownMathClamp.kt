package com.letta.mobile.feature.chat.render

import androidx.annotation.VisibleForTesting

private const val MAX_SIMPLE_INLINE_MATH_CHARS = 3

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
        if (!isDisplayMathDelimiterAt(line, i)) {
            i++
            continue
        }
        openIdx = if (openIdx >= 0) -1 else i
        i += 2
    }
    return openIdx
}

private fun isDisplayMathDelimiterAt(line: String, index: Int): Boolean =
    line[index] == '$' && line[index + 1] == '$'

private fun findUnmatchedInlineMathOpenerInLine(line: String): Int {
    val opener = findLastInlineMathOpener(line)
    if (opener < 0) return -1
    val body = line.substring(opener + 1)
    return if (isLikelyIncompleteInlineMathBody(body)) opener else -1
}

private fun findLastInlineMathOpener(line: String): Int {
    var opener = -1
    var i = 0
    while (i < line.length) {
        if (line.isSingleDollarAt(i) && !line.isInsideInlineCodeAt(i)) {
            opener = resolveInlineMathOpener(line, i, opener)
        }
        i++
    }
    return opener
}

private fun resolveInlineMathOpener(line: String, index: Int, currentOpener: Int): Int {
    if (closesPriorInlineMathOpener(line, index, currentOpener)) return -1
    return if (canOpenInlineMathAt(line, index)) index else currentOpener
}

private fun closesPriorInlineMathOpener(line: String, index: Int, currentOpener: Int): Boolean {
    if (currentOpener < 0) return false
    if (index <= 0) return false
    return !line[index - 1].isWhitespace()
}

private fun canOpenInlineMathAt(line: String, index: Int): Boolean {
    val next = index + 1
    if (next >= line.length) return false
    if (line[next].isWhitespace()) return false
    if (line[next].isDigit()) return false
    if (index > 0 && line[index - 1].isWordChar()) return false
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
