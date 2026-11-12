package com.letta.mobile.data.voice.speech

/**
 * Strips what should not be read aloud from one segment of reply text. A tag is what the chunker
 * treats as one: `<` followed by a letter or `/`, up to the next `>`; comparisons such as
 * `2 < 3 > 1` survive. Unfinished markup at the end of a
 * segment is never spoken either: an unfinished tag is dropped, an unfinished link is reduced to
 * its text, and the orphaned target of a link whose `[` was released earlier is dropped. Leftover
 * brackets are never read.
 */
internal object SpeakableText {
    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("""!\[([^\]]*)]\([^)]*\)""") to "$1",
        Regex("""\[([^\]]+)]\([^)]*\)""") to "$1",
        Regex("""\[([^\]]+)]\([^)]*$""") to "$1",
        Regex("""\[([^\]]*)$""") to "$1",
        Regex("""]\([^)]*(?:\)|$)""") to "",
        Regex("""[\[\]]""") to "",
        Regex("""<[A-Za-z/][^>]*>""") to "",
        Regex("""<[A-Za-z/][^>]*$""") to "",
        Regex("""https?://\S+""") to "",
        Regex("""^\s*#{1,6}\s*""") to "",
        Regex("""^\s*>\s?""") to "",
        Regex("""^\s*(?:[-*+]|\d+[.)])(?:\s+|$)""") to "",
        Regex("""^[\s|:-]+$""") to "",
        Regex("""\s*\|\s*""") to ", ",
        Regex("""\*{1,3}|~~|`""") to "",
        Regex("""(?<![A-Za-z0-9])_{1,3}|_{1,3}(?![A-Za-z0-9])""") to "",
        Regex("""\s+""") to " ",
    )

    /** [raw] without markdown, links, URLs or HTML, trimmed; empty when nothing speakable is left. */
    fun clean(raw: String): String =
        rules.fold(raw.trim()) { text, (pattern, replacement) -> pattern.replace(text, replacement) }
            .trim()
            .trim(',', ' ')
}
