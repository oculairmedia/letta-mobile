package com.letta.mobile.feature.chat.render

private const val MAX_SIMPLE_INLINE_MATH_CHARS = 3

internal data class MarkdownMathScanLine(val raw: String)

internal data class MarkdownMathScanIndex(val value: Int)

internal data class MarkdownMathScanCursor(
    val line: MarkdownMathScanLine,
    val index: MarkdownMathScanIndex,
)

internal data class MarkdownMathInlineBodySpan(
    val line: MarkdownMathScanLine,
    val start: MarkdownMathScanIndex,
)

internal fun findUnmatchedMathOpenerInLine(line: MarkdownMathScanLine): Int {
    val displayOpenIdx = findUnmatchedDisplayMathOpenerInLine(line)
    val inlineOpenIdx = findUnmatchedInlineMathOpenerInLine(line)
    return when {
        displayOpenIdx.value < 0 -> inlineOpenIdx.value
        inlineOpenIdx.value < 0 -> displayOpenIdx.value
        else -> minOf(displayOpenIdx.value, inlineOpenIdx.value)
    }
}

private fun findUnmatchedDisplayMathOpenerInLine(line: MarkdownMathScanLine): MarkdownMathScanIndex {
    var openIdx = MarkdownMathScanIndex(-1)
    var cursor = MarkdownMathScanCursor(line, MarkdownMathScanIndex(0))
    while (cursor.index.value <= line.raw.length - 2) {
        if (line.raw[cursor.index.value] == '\\') {
            cursor = cursor.advanceBy(2)
            continue
        }
        if (!isDisplayMathDelimiterAt(cursor)) {
            cursor = cursor.advanceBy(1)
            continue
        }
        openIdx = if (openIdx.value >= 0) MarkdownMathScanIndex(-1) else cursor.index
        cursor = cursor.advanceBy(2)
    }
    return openIdx
}

private fun isDisplayMathDelimiterAt(cursor: MarkdownMathScanCursor): Boolean {
    val index = cursor.index.value
    return cursor.line.raw[index] == '$' && cursor.line.raw[index + 1] == '$'
}

private fun findUnmatchedInlineMathOpenerInLine(line: MarkdownMathScanLine): MarkdownMathScanIndex {
    val opener = findLastInlineMathOpener(line)
    if (opener.value < 0) return opener
    val body = MarkdownMathInlineBodySpan(line, MarkdownMathScanIndex(opener.value + 1))
    return if (isLikelyIncompleteInlineMathBody(body)) opener else MarkdownMathScanIndex(-1)
}

private fun findLastInlineMathOpener(line: MarkdownMathScanLine): MarkdownMathScanIndex {
    var opener = MarkdownMathScanIndex(-1)
    var cursor = MarkdownMathScanCursor(line, MarkdownMathScanIndex(0))
    while (cursor.index.value < line.raw.length) {
        if (line.isSingleDollarAt(cursor.index) && !line.isInsideInlineCodeAt(cursor.index)) {
            opener = resolveInlineMathOpener(cursor, opener)
        }
        cursor = cursor.advanceBy(1)
    }
    return opener
}

private fun resolveInlineMathOpener(
    cursor: MarkdownMathScanCursor,
    currentOpener: MarkdownMathScanIndex,
): MarkdownMathScanIndex {
    if (closesPriorInlineMathOpener(cursor, currentOpener)) return MarkdownMathScanIndex(-1)
    return if (canOpenInlineMathAt(cursor)) cursor.index else currentOpener
}

private fun closesPriorInlineMathOpener(
    cursor: MarkdownMathScanCursor,
    currentOpener: MarkdownMathScanIndex,
): Boolean {
    if (currentOpener.value < 0) return false
    if (cursor.index.value <= 0) return false
    return !cursor.line.raw[cursor.index.value - 1].isWhitespace()
}

private fun canOpenInlineMathAt(cursor: MarkdownMathScanCursor): Boolean {
    val index = cursor.index.value
    val next = index + 1
    if (next >= cursor.line.raw.length) return false
    if (cursor.line.raw[next].isWhitespace()) return false
    if (cursor.line.raw[next].isDigit()) return false
    if (index > 0 && cursor.line.raw[index - 1].isWordChar()) return false
    return true
}

private fun isLikelyIncompleteInlineMathBody(body: MarkdownMathInlineBodySpan): Boolean {
    if (!hasNonBlankNonWhitespaceTail(body)) return false
    if (bodyContainsMathTerminator(body)) return false
    if (bodyContainsMathSyntax(body)) return true
    if (isShellLikeVariable(body)) return false
    return isSimpleInlineMathToken(body)
}

private fun hasNonBlankNonWhitespaceTail(body: MarkdownMathInlineBodySpan): Boolean {
    val raw = body.line.raw
    if (raw.substring(body.start.value).isBlank()) return false
    return !raw[raw.lastIndex].isWhitespace()
}

private fun bodyContainsMathTerminator(body: MarkdownMathInlineBodySpan): Boolean {
    for (index in body.start.value until body.line.raw.length) {
        val ch = body.line.raw[index]
        if (ch == '$' || ch == '\n') return true
    }
    return false
}

private fun bodyContainsMathSyntax(body: MarkdownMathInlineBodySpan): Boolean {
    for (index in body.start.value until body.line.raw.length) {
        val ch = body.line.raw[index]
        if (ch in "\\^_{}=+-*/<>(),[]") return true
    }
    return false
}

private fun isShellLikeVariableChar(ch: Char): Boolean {
    if (ch.isUpperCase()) return true
    if (ch.isDigit()) return true
    if (ch == '_') return true
    return false
}

private fun isShellLikeVariable(body: MarkdownMathInlineBodySpan): Boolean {
    for (index in body.start.value until body.line.raw.length) {
        if (!isShellLikeVariableChar(body.line.raw[index])) return false
    }
    return true
}

private fun bodyLength(body: MarkdownMathInlineBodySpan): Int =
    body.line.raw.length - body.start.value

private fun bodyAllLetters(body: MarkdownMathInlineBodySpan): Boolean {
    for (index in body.start.value until body.line.raw.length) {
        if (!body.line.raw[index].isLetter()) return false
    }
    return true
}

private fun bodyContainsLowercase(body: MarkdownMathInlineBodySpan): Boolean {
    for (index in body.start.value until body.line.raw.length) {
        if (body.line.raw[index].isLowerCase()) return true
    }
    return false
}

private fun isSimpleInlineMathToken(body: MarkdownMathInlineBodySpan): Boolean {
    val length = bodyLength(body)
    if (length > MAX_SIMPLE_INLINE_MATH_CHARS) return false
    if (!bodyAllLetters(body)) return false
    if (length == 1) return true
    return bodyContainsLowercase(body)
}

private fun MarkdownMathScanCursor.advanceBy(delta: Int): MarkdownMathScanCursor =
    copy(index = MarkdownMathScanIndex(index.value + delta))

private fun MarkdownMathScanLine.isSingleDollarAt(index: MarkdownMathScanIndex): Boolean =
    raw.isSingleDollarAt(index.value)

private fun MarkdownMathScanLine.isInsideInlineCodeAt(index: MarkdownMathScanIndex): Boolean =
    raw.isInsideInlineCodeAt(index.value)
