package com.letta.mobile.ui.components

/**
 * Render-only repair pass for incomplete Markdown at the streaming tail.
 *
 * AI responses arrive as arbitrary text edits: a chunk may end after `**bold`,
 * `[link](`, `$$x`, or inside a fenced code block. Standard Markdown parsers
 * are document parsers, so they either leak the literal delimiters or let an
 * open block consume everything that follows. This pass keeps the source of
 * truth untouched and produces a temporary, syntactically-closed string only
 * for rendering the active block.
 */
internal fun repairIncompleteMarkdownForStreaming(text: String): String {
    if (text.isEmpty()) return text

    repairOpenCodeFence(text)?.let { return it }
    repairOpenDisplayMath(text)?.let { return it }
    repairOpenInlineMath(text)?.let { return it }

    val linkRepaired = repairDanglingLinkOrImage(text)
    return linkRepaired + inlineMarkdownClosersForLastLine(linkRepaired)
}

private fun repairOpenCodeFence(text: String): String? {
    if (!hasOpenCodeFence(text)) return null
    val builder = StringBuilder(text)
    if (!text.endsWith('\n')) builder.append('\n')
    builder.append("```")
    return builder.toString()
}

private fun repairOpenDisplayMath(text: String): String? {
    if (!hasOpenDisplayMathFence(text)) return null
    return if (text.endsWith('$')) {
        text + "$"
    } else {
        text + "$$"
    }
}

private fun hasOpenCodeFence(text: String): Boolean {
    var open = false
    var lineStart = true
    var i = 0
    while (i <= text.length - 3) {
        val c = text[i]
        if (lineStart) {
            var j = i
            var spaces = 0
            while (j < text.length && text[j] == ' ' && spaces < 4) {
                spaces++
                j++
            }
            if (j <= text.length - 3 &&
                text[j] == '`' &&
                text[j + 1] == '`' &&
                text[j + 2] == '`'
            ) {
                open = !open
                i = j + 3
                lineStart = false
                continue
            }
        }
        lineStart = c == '\n'
        i++
    }
    return open
}

private fun hasOpenDisplayMathFence(text: String): Boolean {
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

private fun repairOpenInlineMath(text: String): String? {
    val lineStart = text.lastIndexOf('\n') + 1
    val line = text.substring(lineStart)
    val opener = findUnclosedInlineMathOpener(line)
    if (opener < 0) return null

    val body = line.substring(opener + 1)
    if (!isLikelyInlineMathBody(body)) return null
    return text + "$"
}

private fun findUnclosedInlineMathOpener(line: String): Int {
    var opener = -1
    var i = 0
    while (i < line.length) {
        if (isSingleDollarAt(line, i) && !line.isInsideInlineCodeAt(i)) {
            when {
                opener >= 0 && canCloseInlineMathAt(line, i) -> opener = -1
                canOpenInlineMathAt(line, i) -> opener = i
            }
        }
        i++
    }
    return opener
}

private fun isSingleDollarAt(line: String, index: Int): Boolean {
    if (line[index] != '$' || line.isEscapedAt(index)) return false
    if (index > 0 && line[index - 1] == '$') return false
    if (index + 1 < line.length && line[index + 1] == '$') return false
    return true
}

private fun canOpenInlineMathAt(line: String, index: Int): Boolean {
    val next = index + 1
    if (next >= line.length) return false
    if (line[next].isWhitespace() || line[next].isDigit()) return false
    if (index > 0 && line[index - 1].isWordChar()) return false
    return true
}

private fun canCloseInlineMathAt(line: String, index: Int): Boolean {
    return index > 0 && !line[index - 1].isWhitespace()
}

private fun isLikelyInlineMathBody(body: String): Boolean {
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

private fun repairDanglingLinkOrImage(text: String): String {
    val lineStart = text.lastIndexOf('\n') + 1
    val line = text.substring(lineStart)
    val openBracket = findLastUnescapedOutsideInlineCode(line, '[')
    if (openBracket < 0) return text

    val absoluteOpen = lineStart + openBracket
    val isImage = absoluteOpen > 0 && text[absoluteOpen - 1] == '!'
    val removalStart = if (isImage) absoluteOpen - 1 else absoluteOpen
    val closeBracket = findNextUnescaped(line, ']', openBracket + 1)

    if (closeBracket < 0) {
        val visibleText = if (isImage) "" else line.substring(openBracket + 1)
        return text.substring(0, removalStart) + visibleText
    }

    val afterBracket = closeBracket + 1
    if (afterBracket < line.length && line[afterBracket] == '(') {
        val closeParen = findNextUnescaped(line, ')', afterBracket + 1)
        if (closeParen < 0) {
            val visibleText = if (isImage) "" else line.substring(openBracket + 1, closeBracket)
            return text.substring(0, removalStart) + visibleText
        }
    }

    return text
}

private fun inlineMarkdownClosersForLastLine(text: String): String {
    val lineStart = text.lastIndexOf('\n') + 1
    val line = text.substring(lineStart)
    if (line.isEmpty()) return ""
    if (line.isCodeFenceMarkerLine()) return ""

    val stack = mutableListOf<String>()
    var i = 0
    while (i < line.length) {
        if (line[i] == '\\') {
            i += 2
            continue
        }

        val backtickRun = backtickRunLength(line, i)
        if (backtickRun > 0) {
            val marker = "`".repeat(backtickRun)
            val close = line.indexOf(marker, startIndex = i + backtickRun)
            if (close < 0) {
                stack.add(marker)
                break
            }
            i = close + backtickRun
            continue
        }

        val marker = markdownMarkerAt(line, i)
        if (marker != null) {
            val canClose = stack.lastOrNull() == marker && canCloseMarker(line, i)
            if (canClose) {
                stack.removeAt(stack.lastIndex)
                i += marker.length
                continue
            }
            if (canOpenMarker(line, i, marker)) {
                stack.add(marker)
                i += marker.length
                continue
            }
            i += marker.length
            continue
        }

        i++
    }

    if (stack.isEmpty()) return ""
    return buildString {
        for (i in stack.lastIndex downTo 0) append(stack[i])
    }
}

private fun String.isCodeFenceMarkerLine(): Boolean {
    val trimmed = trimStart()
    return trimmed.startsWith("```") || trimmed.startsWith("~~~")
}

private fun backtickRunLength(line: String, index: Int): Int {
    if (line[index] != '`') return 0
    var end = index
    while (end < line.length && line[end] == '`') end++
    return end - index
}

private fun markdownMarkerAt(line: String, index: Int): String? {
    val remaining = line.length - index
    if (remaining >= 3) {
        val triple = line.substring(index, index + 3)
        if (triple == "***" || triple == "___") return triple
    }
    if (remaining >= 2) {
        val pair = line.substring(index, index + 2)
        if (pair == "**" || pair == "__" || pair == "~~") return pair
    }
    return when (line[index]) {
        '*' -> "*"
        '_' -> "_"
        else -> null
    }
}

private fun canOpenMarker(line: String, index: Int, marker: String): Boolean {
    val next = index + marker.length
    if (next >= line.length) return false
    if (line[next].isWhitespace()) return false
    if ((marker.first() == '_' || marker.first() == '*') &&
        index > 0 &&
        line[index - 1].isLetterOrDigit() &&
        line[next].isLetterOrDigit()
    ) {
        return false
    }
    return true
}

private fun canCloseMarker(line: String, index: Int): Boolean {
    return index > 0 && !line[index - 1].isWhitespace()
}

private fun findLastUnescaped(text: String, target: Char): Int {
    var i = text.lastIndex
    while (i >= 0) {
        if (text[i] == target && !text.isEscapedAt(i)) return i
        i--
    }
    return -1
}

private fun findLastUnescapedOutsideInlineCode(text: String, target: Char): Int {
    var i = text.lastIndex
    while (i >= 0) {
        if (text[i] == target && !text.isEscapedAt(i) && !text.isInsideInlineCodeAt(i)) return i
        i--
    }
    return -1
}

private fun findNextUnescaped(text: String, target: Char, startIndex: Int): Int {
    var i = startIndex
    while (i < text.length) {
        if (text[i] == target && !text.isEscapedAt(i)) return i
        i++
    }
    return -1
}

private fun String.isEscapedAt(index: Int): Boolean {
    var slashCount = 0
    var i = index - 1
    while (i >= 0 && this[i] == '\\') {
        slashCount++
        i--
    }
    return slashCount % 2 == 1
}

private fun String.isInsideInlineCodeAt(index: Int): Boolean {
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

private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '_'

private const val MAX_SIMPLE_INLINE_MATH_CHARS = 3
