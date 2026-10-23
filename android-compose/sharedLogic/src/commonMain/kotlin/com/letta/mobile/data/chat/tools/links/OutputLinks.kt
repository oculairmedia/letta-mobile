package com.letta.mobile.data.chat.tools.links

/**
 * The URLs and file paths in a tool's output, so a step's inset can make them links: a URL to open
 * in the browser, a path (with an optional `:line`) to open in the file preview. A path needs a
 * directory part (`src/Main.kt`, `/repo/a.kt`, `C:\repo\a.kt`) and an extension, so plain words and
 * numbers never read as paths.
 */
object OutputLinks {
    /** One link found in an output. */
    sealed interface Match {
        val value: String
        val range: IntRange

        data class Url(override val value: String, override val range: IntRange) : Match

        /** A path, and the line it names after a `:` when it names one. */
        data class Path(override val value: String, override val range: IntRange, val path: String, val line: Int?) : Match
    }

    /** Every link in [text], in order and never overlapping. */
    fun find(text: String): List<Match> = LINK.findAll(text).map(::matchOf).toList()

    private fun matchOf(found: MatchResult): Match {
        val url = found.groups[URL_GROUP]
        if (url != null) {
            // A sentence's closing punctuation is not part of the URL it ends on.
            val trimmed = url.value.trimEnd { it in TRAILING_PUNCTUATION }
            return Match.Url(trimmed, url.range.first until url.range.first + trimmed.length)
        }
        val path = found.groupValues[PATH_GROUP]
        val line = found.groups[LINE_GROUP]?.value?.toIntOrNull()
        return Match.Path(found.value, found.range, path, line)
    }

    private const val URL_GROUP = 1
    private const val PATH_GROUP = 2
    private const val LINE_GROUP = 3

    private const val TRAILING_PUNCTUATION = ".,;:)]'\""

    private val LINK = Regex(
        """(https?://[^\s<>"'`]+)""" +
            """|((?:(?:[A-Za-z]:\\|\.{0,2}/)(?:[\w.@-]+[/\\])*|(?:[\w.@-]+[/\\])+)[\w.@-]+\.[A-Za-z0-9]{1,8})(?::(\d+))?""",
    )
}
