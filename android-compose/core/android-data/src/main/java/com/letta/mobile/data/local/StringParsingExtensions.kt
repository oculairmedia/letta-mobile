package com.letta.mobile.data.local

/**
 * Splits a string by a delimiter, trims the whitespace from each element,
 * and filters out any blank elements, all in a single pass without intermediate
 * collection allocations.
 */
internal fun String.splitTrimAndFilter(delimiter: String): List<String> {
    if (this.isBlank()) return emptyList()

    val result = mutableListOf<String>()
    var startIndex = 0
    val delimLen = delimiter.length

    while (startIndex < this.length) {
        val delimIndex = this.indexOf(delimiter, startIndex)
        val endIndex = if (delimIndex == -1) this.length else delimIndex

        var start = startIndex
        var end = endIndex - 1

        // Trim leading whitespace
        while (start <= end && this[start].isWhitespace()) {
            start++
        }

        // Trim trailing whitespace
        while (end >= start && this[end].isWhitespace()) {
            end--
        }

        // Add if not blank
        if (start <= end) {
            result.add(this.substring(start, end + 1))
        }

        if (delimIndex == -1) break
        startIndex = delimIndex + delimLen
    }

    return result
}
