package com.letta.mobile.data.chat.projection

/** A bounded, plain-text preview only; classification and copying use the original event. */
fun monitorDisplayLines(event: String): List<String> {
    // A complete comment may span lines; an upstream-truncated one must not consume the next item.
    val lines = CLOSED_HTML_COMMENT.replace(event, ::dangerCounts)
        .lineSequence().map(::monitorDisplayLine).filter(String::isNotBlank).toList()
    return lines.take(DISPLAY_LIMIT) + if (lines.size > DISPLAY_LIMIT) listOf("+${lines.size - DISPLAY_LIMIT} more") else emptyList()
}

private fun monitorDisplayLine(line: String): String {
    var text = HTML_COMMENT.replace(line, ::dangerCounts)
    text = decodeEntities(HTML_TAG.replace(text, " "))
    text = MARKDOWN_COMMENT.replace(text, "")
    text = MARKDOWN_IMAGE.replace(text, "$1")
    text = MARKDOWN_LINK.replace(text, "$1")
    // WatchPR truncates bodies before appending the permalink, sometimes midway through a link.
    text = PARTIAL_LINK.replace(text, "$1")
    text = URL.replace(text) { it.value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#') }
    text = FILE_PATH.replace(text) { " on ${it.groupValues[1]}" }
    text = HEADING.replace(text, " ")
    text = EMPHASIS.replace(text.replace("`", ""), "$1")
    return SPACE.replace(text, " ")
        .trim().removeSuffix("(truncated)").trimEnd('…', ' ')
}

/** Danger puts its useful counts inside an otherwise private HTML comment; any other comment goes. */
private fun dangerCounts(comment: MatchResult): String =
    DANGER_COUNTS.find(comment.value)?.let { "${it.groupValues[1]} failure, ${it.groupValues[2]} warning" }.orEmpty()

/** The entities GitHub bodies carry once one XML level is decoded upstream; `&amp;` last. */
private fun decodeEntities(text: String): String = ENTITIES.fold(text) { acc, (entity, char) -> acc.replace(entity, char) }
    .replace("&amp;", "&")

private const val DISPLAY_LIMIT = 5
private val CLOSED_HTML_COMMENT = Regex("<!--(?:(?!<!--)[\\s\\S])*?-->")
private val ENTITIES = listOf("&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&nbsp;" to " ")
private val HEADING = Regex("(?:^|\\s)#{1,6}\\s+")
private val EMPHASIS = Regex("\\*{1,3}([^*\\s][^*]*?)\\*{1,3}|\\*{2,3}")
private val HTML_COMMENT = Regex("<!--[\\s\\S]*?(?:-->|$)")
private val DANGER_COUNTS = Regex("(\\d+) failure: (\\d+) warning")
private val HTML_TAG = Regex("</?[A-Za-z][^>]*>")
private val MARKDOWN_COMMENT = Regex("\\[//\\]:\\s*#\\s*\\([^)]*\\)")
private val MARKDOWN_IMAGE = Regex("!\\[([^\\]]*)\\]\\([^)]*\\)")
private val MARKDOWN_LINK = Regex("\\[([^\\[\\]]*)\\]\\([^)]*\\)")
private val PARTIAL_LINK = Regex("\\[([^\\[\\]]*)\\]\\(https?://\\S*")
private val URL = Regex("https?://[^\\s<>]+")
private val FILE_PATH = Regex(" on (?:[\\w.-]+/)+([^\\s/:]+:\\d+)")
private val SPACE = Regex("\\s+")
