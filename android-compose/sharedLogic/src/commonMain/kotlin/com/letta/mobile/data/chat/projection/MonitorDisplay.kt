package com.letta.mobile.data.chat.projection

/** A bounded, plain-text preview only; classification and copying use the original event. */
fun monitorDisplayLines(event: String): List<String> {
    // An upstream-truncated comment must not consume the next item in the batch.
    val lines = event.lineSequence().map(::monitorDisplayLine).filter(String::isNotBlank).toList()
    return lines.take(DISPLAY_LIMIT) + if (lines.size > DISPLAY_LIMIT) listOf("+${lines.size - DISPLAY_LIMIT} more") else emptyList()
}

private fun monitorDisplayLine(line: String): String {
    var text = HTML_COMMENT.replace(line) { comment ->
        // Danger puts its useful counts inside an otherwise private HTML comment.
        DANGER_COUNTS.find(comment.value)?.let { "${it.groupValues[1]} failure, ${it.groupValues[2]} warning" }.orEmpty()
    }
    text = HTML_TAG.replace(text, " ")
    text = MARKDOWN_COMMENT.replace(text, "")
    text = MARKDOWN_IMAGE.replace(text, "$1")
    text = MARKDOWN_LINK.replace(text, "$1")
    // WatchPR truncates bodies before appending the permalink, sometimes midway through a link.
    text = PARTIAL_LINK.replace(text, "$1")
    text = URL.replace(text) { it.value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#') }
    text = FILE_PATH.replace(text) { " on ${it.groupValues[1]}" }
    return SPACE.replace(text.replace("**", "").replace("`", "").replace("## ", ""), " ")
        .trim().removeSuffix("(truncated)").trimEnd('…', ' ')
}

private const val DISPLAY_LIMIT = 5
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
