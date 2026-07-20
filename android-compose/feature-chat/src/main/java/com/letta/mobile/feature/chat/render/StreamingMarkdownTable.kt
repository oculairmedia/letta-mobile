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
        var previousLine: TextLineSlice? = null
        var lineStart = 0

        while (lineStart <= text.length) {
            val newlineIndex = text.indexOf('\n', startIndex = lineStart)
            val rawEnd = if (newlineIndex >= 0) newlineIndex else text.length
            val line = TextLineSlice(text, lineStart, rawEnd)
            val contentStart = line.firstNonWhitespaceIndex()
            if (contentStart < rawEnd) {
                val contentEnd = line.lastNonWhitespaceExclusive(contentStart)
                if (isTableHeaderSeparatorPair(previousLine, line, contentStart, contentEnd)) {
                    return true
                }
                previousLine = TextLineSlice(text, contentStart, contentEnd)
            }
            if (newlineIndex < 0) break
            lineStart = newlineIndex + 1
        }
        return false
    }

    private fun isTableHeaderSeparatorPair(
        previousLine: TextLineSlice?,
        currentLine: TextLineSlice,
        contentStart: Int,
        contentEnd: Int,
    ): Boolean {
        if (previousLine == null) return false
        if (!previousLine.containsPipe(previousLine.start, previousLine.endExclusive)) {
            return false
        }
        return currentLine.looksLikeMarkdownTableSeparator(contentStart, contentEnd)
    }
}
