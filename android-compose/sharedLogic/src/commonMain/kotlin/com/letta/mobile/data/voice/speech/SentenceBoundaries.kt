package com.letta.mobile.data.voice.speech

/** Where a sentence ends in reply text, and where to cut text that is too long. */
internal object SentenceBoundaries {
    private const val NOT_FOUND = -1
    private val terminators = setOf('.', '!', '?', '…')
    private val closers = setOf('"', '\'', ')', ']', '*', '_', '”', '’', '`')
    private val abbreviations = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "inc", "ltd", "co", "no",
        "approx", "fig", "e.g", "i.e", "u.s", "a.m", "p.m",
    )

    /**
     * The end (exclusive) of the first complete sentence in [text] before [limit], or
     * [NOT_FOUND]. A terminator at the very end of the text ends a sentence only when [final].
     */
    fun find(text: CharSequence, limit: Int, final: Boolean): Int {
        for (index in 0 until limit) {
            val end = endAt(text, index, limit, final)
            if (end != NOT_FOUND) return end
        }
        return NOT_FOUND
    }

    private fun endAt(text: CharSequence, index: Int, limit: Int, final: Boolean): Int {
        val char = text[index]
        if (char == '\n') return index + 1
        if (char !in terminators) return NOT_FOUND
        val after = skipClosers(text, index + 1, limit)
        return when {
            after < limit -> endsBefore(text, index, after)
            after < text.length -> after
            final -> after
            else -> NOT_FOUND
        }
    }

    private fun endsBefore(text: CharSequence, terminator: Int, after: Int): Int =
        if (text[after].isWhitespace() && !isAbbreviation(text, terminator)) after else NOT_FOUND

    private fun skipClosers(text: CharSequence, from: Int, limit: Int): Int {
        var position = from
        while (position < limit && isCloser(text[position])) position++
        return position
    }

    private fun isCloser(char: Char): Boolean = char in closers || char in terminators

    private fun isAbbreviation(text: CharSequence, terminator: Int): Boolean {
        if (text[terminator] != '.') return false
        val start = wordStart(text, terminator)
        val word = text.substring(start, terminator)
        return when {
            word.lowercase() in abbreviations -> true
            isInitial(word) -> true
            else -> isListNumber(text, start, word)
        }
    }

    private fun wordStart(text: CharSequence, end: Int): Int {
        var start = end
        while (start > 0 && isWordChar(text[start - 1])) start--
        return start
    }

    private fun isWordChar(char: Char): Boolean = char.isLetterOrDigit() || char == '.'

    private fun isInitial(word: String): Boolean = word.singleOrNull()?.let { it.isUpperCase() && it != 'I' } == true

    private fun isListNumber(text: CharSequence, start: Int, word: String): Boolean {
        if (word.isEmpty() || !word.all(Char::isDigit)) return false
        return start == 0 || text[start - 1] == '\n'
    }
}

private val ClauseBreaks = setOf(',', ';', ':')

/** Where to cut [text] so the first part is at most [max] characters: a clause break, else a space. */
internal fun softCut(text: CharSequence, max: Int): Int {
    val window = text.subSequence(0, max)
    val clause = window.indexOfLast { it in ClauseBreaks }
    if (clause > 0) return clause + 1
    val space = window.indexOfLast(Char::isWhitespace)
    return if (space > 0) space + 1 else max
}
