package com.letta.mobile.data.canvas.compose

/**
 * A non-blank line's content after its indentation, starting at [start] in the input, and the
 * block syntax it opens. [indent] is the line's indentation in columns; [nested] whether that puts
 * it inside the open list item.
 */
internal class MdLine(val text: String, val start: Int, val indent: Int = 0, val nested: Boolean = false) {
    val source: SourceLine get() = SourceLine(text, start)

    fun isQuote(): Boolean = text.startsWith(">")

    fun isThematicBreak(): Boolean = THEMATIC_BREAK.matches(text)

    fun isSetextUnderline(): Boolean = SETEXT.matches(text)

    fun startsTable(): Boolean = text.startsWith("|")

    fun isTableDelimiter(): Boolean = '|' in text && TABLE_DELIMITER.matches(text)

    fun isFootnoteDefinition(): Boolean = text.startsWith("[^") && DEFINITION.containsMatchIn(text)

    fun isLinkDefinition(): Boolean = DEFINITION.containsMatchIn(text)

    /** The line past its first [count] characters (all of them, at most). */
    fun drop(count: Int): MdLine {
        val dropped = count.coerceAtMost(text.length)
        return MdLine(text.substring(dropped), start + dropped)
    }

    /** The GFM task box (`[ ]`, `[x]`) an item's content starts with, if any. */
    fun task(): MatchResult? = TASK.find(text)

    fun heading(): MdHeading? {
        val match = ATX_HEADING.matchEntire(text) ?: return null
        val raw = match.groupValues[2]
        // The heading's text runs to the end of the line.
        return MdHeading(match.groupValues[1].length, SourceLine(withoutClosingSequence(raw), start + text.length - raw.length))
    }

    fun fenceOpening(): MdFence? {
        val char = text.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
        val length = text.takeWhile { it == char }.length
        if (length < 3) return null
        // CommonMark: a backtick fence's info string has no backtick (otherwise it is inline code).
        if (char == '`' && text.drop(length).contains('`')) return null
        return MdFence(char, length, indent, start)
    }

    fun listMarker(): MdListMarker? = bulletMarker() ?: orderedMarker()

    /** The text after a quote's `>` (and one space), or null when the quote line holds nothing. */
    fun quoteContent(): MdLine? {
        val afterMarker = drop(1)
        val inner = if (afterMarker.text.startsWith(" ")) afterMarker.drop(1) else afterMarker
        val body = inner.text.trim()
        if (body.isEmpty()) return null
        return MdLine(body, inner.start + inner.text.length - inner.text.trimStart().length)
    }

    /** Whether this line, inside a quote, would start a block (a quote holds plain text only). */
    fun startsBlock(): Boolean =
        isQuote() || fenceOpening() != null || isThematicBreak() || heading() != null || listMarker() != null

    private fun bulletMarker(): MdListMarker? {
        val match = BULLET.find(text) ?: return null
        return MdListMarker(ordered = false, number = 0, width = markerWidth(match.groupValues[1].length), empty = match.value.length == text.length)
    }

    private fun orderedMarker(): MdListMarker? {
        val match = ORDERED.find(text) ?: return null
        val number = match.groupValues[1].toIntOrNull() ?: return null
        val width = markerWidth(match.groupValues[1].length + 1)
        return MdListMarker(ordered = true, number = number, width = width, empty = match.value.length == text.length)
    }

    /** The marker and the spaces after it (one when the item is empty or the gap is code-sized). */
    private fun markerWidth(markerLength: Int): Int {
        val spaces = text.drop(markerLength).takeWhile { it == ' ' || it == '\t' }.length
        val gapIsSpacing = spaces in 1..MAX_MARKER_GAP && markerLength + spaces < text.length
        return markerLength + if (gapIsSpacing) spaces else 1
    }

    private companion object {
        const val MAX_MARKER_GAP = 4
        val ATX_HEADING = Regex("""^(#{1,6})(?:[ \t]+(.*))?$""")
        val THEMATIC_BREAK = Regex("""^(?:(?:-[ \t]*){3,}|(?:\*[ \t]*){3,}|(?:_[ \t]*){3,})$""")
        val SETEXT = Regex("""^(?:=+|-+)$""")
        val TABLE_DELIMITER = Regex("""^\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?$""")
        val DEFINITION = Regex("""^\[[^\]]+]:""")
        val BULLET = Regex("""^([-*+])(?:[ \t]+|$)""")
        val ORDERED = Regex("""^(\d{1,9})[.)](?:[ \t]+|$)""")
        val TASK = Regex("""^\[([ xX])](?:[ \t]+|$)""")

        /** An optional closing sequence of #s, separated by a space (or the whole content). */
        val CLOSING_SEQUENCE = Regex("""(^|\s)#+$""")

        fun withoutClosingSequence(raw: String): String {
            val trimmed = raw.trim()
            val closing = CLOSING_SEQUENCE.find(trimmed) ?: return trimmed
            return trimmed.substring(0, closing.range.first).trim()
        }
    }
}

/** An ATX heading: its level, and its text without the #s. */
internal class MdHeading(val level: Int, val content: SourceLine)

/** A list item's marker; [width] covers the marker and the spaces after it. */
internal class MdListMarker(val ordered: Boolean, val number: Int, val width: Int, private val empty: Boolean) {
    /** CommonMark: inside a paragraph only a non-empty item starts a list, and a numbered one only at 1 ("1984. A good year." is text). */
    fun canInterruptParagraph(): Boolean {
        if (empty) return false
        return !ordered || number == 1
    }

    /** A numbered item opening a list ([position] 1) at a number other than 1. */
    fun startsMisnumbered(position: Int): Boolean {
        if (!ordered) return false
        return position == 1 && number != 1
    }
}

/** An open fenced code block and the lines read into it so far. */
internal class MdFence(private val char: Char, private val length: Int, private val indent: Int, val offset: Int) {
    private val lines = mutableListOf<String>()

    fun closedBy(line: SourceLine): Boolean {
        val body = line.text.trim()
        return line.indentWidth() < 4 && body.length >= length && body.all { it == char }
    }

    /** [line] as code: the fence's own indentation (spaces, up to its width) removed. */
    fun add(line: SourceLine) {
        val spaces = line.text.takeWhile { it == ' ' }.length
        lines += line.text.substring(spaces.coerceAtMost(indent))
    }

    fun code(): MdBlock.Code = MdBlock.Code(lines.joinToString("\n"))
}
