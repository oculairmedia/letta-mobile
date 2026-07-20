package com.letta.mobile.feature.chat.render

/**
 * Half-open [start, endExclusive) slice into [source] for markdown table scanning.
 */
internal data class TextLineSlice(val source: String, val start: Int, val endExclusive: Int) {
    fun firstNonWhitespaceIndex(): Int {
        var index = start
        while (index < endExclusive && source[index].isWhitespace()) {
            index += 1
        }
        return index
    }

    fun lastNonWhitespaceExclusive(from: Int): Int {
        var index = endExclusive
        while (index > from && source[index - 1].isWhitespace()) {
            index -= 1
        }
        return index
    }

    fun containsPipe(from: Int, toExclusive: Int): Boolean {
        for (index in from until toExclusive) {
            if (source[index] == '|') return true
        }
        return false
    }

    fun looksLikeMarkdownTableSeparator(from: Int, toExclusive: Int): Boolean {
        var cellHasDash = false
        var foundCell = false
        var index = from

        while (index < toExclusive) {
            when (source[index]) {
                '|', ' ', '\t' -> {
                    if (source[index] == '|' && cellHasDash) {
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
}

internal fun String.containsMarkdownTable(): Boolean {
    val scan = MarkdownTableScan(this)
    return scan.containsTable()
}

private class MarkdownTableScan(private val text: String) {
    fun containsTable(): Boolean {
        var previousContentLine: TextLineSlice? = null
        var lineStart = 0

        while (lineStart <= text.length) {
            val lineBounds = nextLineBounds(lineStart) ?: break
            val contentLine = trimContentSlice(lineBounds)
            if (contentLine != null && isTableHeaderSeparatorPair(previousContentLine, contentLine)) {
                return true
            }
            if (contentLine != null) {
                previousContentLine = contentLine
            }
            lineStart = lineBounds.endExclusive + 1
        }
        return false
    }

    private data class LineBounds(val start: Int, val endExclusive: Int)

    private fun nextLineBounds(lineStart: Int): LineBounds? {
        if (lineStart > text.length) return null
        val newlineIndex = text.indexOf('\n', startIndex = lineStart)
        val endExclusive = if (newlineIndex >= 0) newlineIndex else text.length
        if (newlineIndex < 0 && lineStart == text.length) return null
        return LineBounds(lineStart, endExclusive)
    }

    private fun trimContentSlice(bounds: LineBounds): TextLineSlice? {
        val line = TextLineSlice(text, bounds.start, bounds.endExclusive)
        val contentStart = line.firstNonWhitespaceIndex()
        if (contentStart >= bounds.endExclusive) return null
        val contentEnd = line.lastNonWhitespaceExclusive(contentStart)
        return TextLineSlice(text, contentStart, contentEnd)
    }

    private fun isTableHeaderSeparatorPair(
        previousLine: TextLineSlice?,
        currentLine: TextLineSlice,
    ): Boolean {
        if (previousLine == null) return false
        if (!previousLine.containsPipe(previousLine.start, previousLine.endExclusive)) return false
        return currentLine.looksLikeMarkdownTableSeparator(
            currentLine.start,
            currentLine.endExclusive,
        )
    }
}
