package com.letta.mobile.data.model

/**
 * The status a Monitor event gets: letta-code sends each event as a `<task-notification>` with no
 * `<status>` and a `<result><event>…</event></result>`, so it is not a completion.
 */
internal const val MONITOR_EVENT_STATUS = "event"

fun extractSubagentNotification(raw: String): UiSubagentNotification? {
    if (raw.indexOf("<task-notification", ignoreCase = true) < 0) return null
    val blockStart = raw.indexOf("<task-notification", ignoreCase = true)
    val blockEnd = raw.indexOf("</task-notification>", startIndex = blockStart, ignoreCase = true)
    val xmlEnd = if (blockEnd >= 0) blockEnd + "</task-notification>".length else raw.length
    val block = raw.substring(blockStart, xmlEnd)
    val tail = raw.substring(xmlEnd)
    val usage = block.xmlTag("usage")
    val result = block.xmlTag("result")
    return UiSubagentNotification(
        toolCallId = block.xmlTag("tool_call_id") ?: block.xmlTag("toolCallId"),
        status = block.notificationStatus(),
        summary = block.xmlTag("summary"),
        result = result,
        usage = usage,
        durationMs = usage?.durationMillisFromUsage(),
        transcriptUri = block.xmlTag("transcript")
            ?: tail.lineAfter("Full transcript available at")
            ?: tail.lineAfter("Full transcript at")
            ?: tail.lineAfter("Full transcript:")
            ?: block.lineAfter("Full transcript available at")
            ?: block.lineAfter("Full transcript at")
            ?: block.lineAfter("Full transcript:"),
        taskId = block.xmlTag("task-id") ?: block.xmlTag("task_id") ?: block.xmlTag("taskId"),
        subagentAgentId = block.notificationAgentId(result),
    )
}

/** The block's status; a Monitor event carries none, and is not a completion. */
private fun String.notificationStatus(): String =
    xmlTag("status") ?: xmlTag("state") ?: if (xmlTag("event") != null) MONITOR_EVENT_STATUS else "completed"

private fun String.notificationAgentId(result: String?): String? =
    xmlTag("agent_id") ?: xmlTag("agentId")
        // Only the labelled result header is identity evidence, never later prose.
        ?: Regex("(?:^|\\s)agent_id=(\\S+)")
            .find(result?.lineSequence()?.firstOrNull().orEmpty())?.groupValues?.get(1)

private fun String.xmlTag(name: String): String? {
    val match = Regex("<$name(?:\\s[^>]*)?>([\\s\\S]*?)</$name>", RegexOption.IGNORE_CASE)
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?: return null
    return match
        .removePrefix("<![CDATA[")
        .removeSuffix("]]>")
        .decodeXmlEntities()
        .trim()
        .takeIf { it.isNotBlank() }
}

private fun String.lineAfter(marker: String): String? {
    val index = indexOf(marker, ignoreCase = true)
    if (index < 0) return null
    val start = index + marker.length
    val end = indexOf('\n', start).let { if (it < 0) length else it }
    return substring(start, end)
        .trim()
        .trimStart(':')
        .trim()
        .decodeXmlEntities()
        .takeIf { it.isNotBlank() }
}

private fun String.decodeXmlEntities(): String =
    replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

private fun String.durationMillisFromUsage(): Long? {
    lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (!trimmed.startsWith("duration_ms", ignoreCase = true)) return@forEach
        val value = trimmed.substringAfter(':', missingDelimiterValue = "")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: return@forEach
        value.toLongOrNull()?.let { return it.coerceAtLeast(0L) }
    }
    return null
}
