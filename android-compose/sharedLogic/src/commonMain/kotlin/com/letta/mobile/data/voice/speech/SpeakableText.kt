package com.letta.mobile.data.voice.speech

/** Strips what should not be read aloud from one segment of reply text. */
internal object SpeakableText {
    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("""!\[([^\]]*)]\([^)]*\)""") to "$1",
        Regex("""\[([^\]]+)]\([^)]*\)""") to "$1",
        Regex("""https?://\S+""") to "",
        Regex("""<[^>\n]+>""") to "",
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
