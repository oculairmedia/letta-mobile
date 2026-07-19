package com.letta.mobile.data.mapper

internal fun String.taskNotificationBlock(): String? {
    val start = indexOf("<task-notification", ignoreCase = true)
    if (start < 0) return null
    val closingTag = "</task-notification>"
    val end = indexOf(closingTag, startIndex = start, ignoreCase = true)
    return if (end >= 0) substring(start, end + closingTag.length) else substring(start)
}

internal fun String.xmlTag(name: String): String? {
    val content = Regex("<$name(?:\\s[^>]*)?>([\\s\\S]*?)</$name>", RegexOption.IGNORE_CASE)
        .find(this)?.groupValues?.getOrNull(1) ?: return null
    return content.removePrefix("<![CDATA[").removeSuffix("]]>")
        .decodeXmlEntities().trim().takeIf(String::isNotBlank)
}

internal fun String.lineAfter(marker: String): String? {
    val markerIndex = indexOf(marker, ignoreCase = true)
    if (markerIndex < 0) return null
    val start = markerIndex + marker.length
    val newline = indexOf('\n', start)
    val end = if (newline < 0) length else newline
    return substring(start, end).trim().trimStart(':').trim()
        .decodeXmlEntities().takeIf(String::isNotBlank)
}

private fun String.decodeXmlEntities(): String =
    replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
