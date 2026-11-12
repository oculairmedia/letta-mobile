package com.letta.mobile.data.voice.speech

import kotlin.jvm.JvmInline

/** Whether more text may still arrive after what is buffered. */
internal enum class ScanMode {
    /** More text may follow: a terminator at the very end is not yet a sentence end. */
    PARTIAL,

    /** Nothing more follows: a terminator at the very end ends the sentence. */
    FINAL,
}

/** A position in the scanned text. */
@JvmInline
internal value class TextIndex(val value: Int) {
    operator fun plus(offset: Int): TextIndex = TextIndex(value + offset)
}

/** A sentence terminator at [at] and the first position after its closing quotes or brackets. */
private data class Terminator(val at: TextIndex, val after: TextIndex)

/** The word ending at a terminator, and where it starts. */
private data class Word(val start: TextIndex, val text: String)

/**
 * Finds where the first complete sentence ends in [text] before [limit]. A sentence ends at a
 * line break, or at a terminator followed by whitespace that does not close an abbreviation, an
 * initial or a list number.
 */
internal class SentenceScan(
    private val text: CharSequence,
    private val limit: Int,
    private val mode: ScanMode,
) {
    /** The end (exclusive) of the first complete sentence, or null when none is complete yet. */
    fun firstEnd(): TextIndex? {
        for (index in 0 until limit) {
            endAt(TextIndex(index))?.let { return it }
        }
        return null
    }

    private fun endAt(index: TextIndex): TextIndex? {
        val char = text[index.value]
        if (char == '\n') return index + 1
        if (char !in Terminators) return null
        val terminator = Terminator(index, skipClosers(index + 1))
        val after = terminator.after.value
        return when {
            after < limit -> endsBefore(terminator)
            after < text.length -> terminator.after
            mode == ScanMode.FINAL -> terminator.after
            else -> null
        }
    }

    private fun endsBefore(terminator: Terminator): TextIndex? {
        if (!text[terminator.after.value].isWhitespace()) return null
        return if (isAbbreviation(terminator)) null else terminator.after
    }

    private fun skipClosers(from: TextIndex): TextIndex {
        var position = from.value
        while (position < limit && text[position].isCloser()) position++
        return TextIndex(position)
    }

    private fun isAbbreviation(terminator: Terminator): Boolean {
        if (text[terminator.at.value] != '.') return false
        val word = wordBefore(terminator)
        return when {
            word.text.lowercase() in Abbreviations -> true
            word.isInitial() -> true
            else -> isListNumber(word)
        }
    }

    private fun wordBefore(terminator: Terminator): Word {
        var start = terminator.at.value
        while (start > 0 && text[start - 1].isWordChar()) start--
        return Word(TextIndex(start), text.substring(start, terminator.at.value))
    }

    private fun isListNumber(word: Word): Boolean {
        if (word.text.isEmpty() || !word.text.all(Char::isDigit)) return false
        return word.start.value == 0 || text[word.start.value - 1] == '\n'
    }

    private fun Word.isInitial(): Boolean = text.singleOrNull()?.let { it.isUpperCase() && it != 'I' } == true

    private fun Char.isCloser(): Boolean = this in Closers || this in Terminators

    private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '.'

    private companion object {
        val Terminators = setOf('.', '!', '?', '…')
        val Closers = setOf('"', '\'', ')', ']', '*', '_', '”', '’', '`')
        val Abbreviations = setOf(
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "inc", "ltd", "co", "no",
            "approx", "fig", "e.g", "i.e", "u.s", "a.m", "p.m",
        )
    }
}

/** Cuts text that is too long for one segment. */
internal class SoftCut(private val max: Int) {
    /** Where to cut [text] so the first part is at most [max] characters: a clause break, else a space. */
    fun indexIn(text: CharSequence): Int {
        val window = text.subSequence(0, max)
        val clause = window.indexOfLast { it in ClauseBreaks }
        if (clause > 0) return clause + 1
        val space = window.indexOfLast(Char::isWhitespace)
        return if (space > 0) space + 1 else max
    }

    /**
     * The position after the last whitespace in [text] that lies outside brackets and parentheses,
     * so a cut never splits a markdown link or its URL; null when there is none.
     */
    fun lastSafeBreak(text: CharSequence): Int? {
        var depth = 0
        var last: Int? = null
        text.forEachIndexed { index, char ->
            depth = (depth + char.nesting()).coerceAtLeast(0)
            if (depth == 0 && char.isWhitespace()) last = index + 1
        }
        return last
    }

    private fun Char.nesting(): Int = when (this) {
        '[', '(' -> 1
        ']', ')' -> -1
        else -> 0
    }

    private companion object {
        val ClauseBreaks = setOf(',', ';', ':')
    }
}
