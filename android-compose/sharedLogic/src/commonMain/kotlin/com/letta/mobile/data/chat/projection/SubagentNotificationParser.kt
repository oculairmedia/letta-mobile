package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiSubagentNotification

fun extractSubagentNotification(raw: String): UiSubagentNotification? {
    if (raw.indexOf("<task-notification", ignoreCase = true) < 0) return null
    val blockStart = raw.indexOf("<task-notification", ignoreCase = true)
    val blockEnd = raw.indexOf("</task-notification>", startIndex = blockStart, ignoreCase = true)
    val xmlEnd = if (blockEnd >= 0) blockEnd + "</task-notification>".length else raw.length
    val block = raw.substring(blockStart, xmlEnd)
    val tail = raw.substring(xmlEnd)
    val usage = block.xmlTag("usage")
    return UiSubagentNotification(
        toolCallId = block.xmlTag("tool_call_id") ?: block.xmlTag("toolCallId"),
        status = block.xmlTag("status") ?: block.xmlTag("state") ?: "completed",
        summary = block.xmlTag("summary"),
        result = block.xmlTag("result"),
        usage = usage,
        durationMs = usage?.durationMillisFromUsage(),
        transcriptUri = block.xmlTag("transcript")
            ?: tail.lineAfter("Full transcript available at")
            ?: tail.lineAfter("Full transcript at")
            ?: tail.lineAfter("Full transcript:"),
        taskId = block.xmlTag("task-id") ?: block.xmlTag("task_id") ?: block.xmlTag("taskId"),
        subagentAgentId = block.xmlTag("agent_id") ?: block.xmlTag("agentId"),
    )
}

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
