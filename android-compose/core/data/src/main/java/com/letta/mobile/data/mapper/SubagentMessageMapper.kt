package com.letta.mobile.data.mapper

import com.letta.mobile.data.model.UiSubagentDispatch
import com.letta.mobile.data.model.UiSubagentNotification
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun extractSubagentDispatch(
    toolCallId: String?,
    arguments: String,
    returnContent: String?,
): UiSubagentDispatch? {
    val args = parseJsonObject(arguments) ?: return null
    val description = args.stringField("description")
        ?: args.stringField("prompt")?.lineSequence()?.firstOrNull()?.take(96)
        ?: "Subagent"
    val subagentType = args.stringField("subagent_type")
        ?: args.stringField("subagentType")
        ?: "agent"
    val prompt = args.stringField("prompt").orEmpty()
    val runInBackground = args["run_in_background"]?.jsonPrimitive?.booleanOrNull
        ?: args["runInBackground"]?.jsonPrimitive?.booleanOrNull
        ?: false
    val result = returnContent?.let(::parseJsonObject)
    return UiSubagentDispatch(
        toolCallId = toolCallId,
        description = description,
        subagentType = subagentType,
        runInBackground = runInBackground,
        prompt = prompt,
        taskId = result?.stringField("task_id") ?: result?.stringField("taskId"),
        subagentAgentId = result?.stringField("subagent_agent_id")
            ?: result?.stringField("subagentAgentId")
            ?: result?.stringField("agent_id")
            ?: result?.stringField("agentId"),
    )
}

internal fun extractSubagentNotification(raw: String): UiSubagentNotification? {
    if (raw.indexOf("<task-notification", ignoreCase = true) < 0) return null
    val blockStart = raw.indexOf("<task-notification", ignoreCase = true)
    val blockEnd = raw.indexOf("</task-notification>", startIndex = blockStart, ignoreCase = true)
    val block = if (blockEnd >= 0) {
        raw.substring(blockStart, blockEnd + "</task-notification>".length)
    } else {
        raw.substring(blockStart)
    }
    return UiSubagentNotification(
        toolCallId = block.xmlTag("tool_call_id") ?: block.xmlTag("toolCallId"),
        status = block.xmlTag("status") ?: block.xmlTag("state") ?: "completed",
        summary = block.xmlTag("summary"),
        result = block.xmlTag("result"),
        usage = block.xmlTag("usage"),
        transcriptUri = block.xmlTag("transcript")
            ?: block.lineAfter("Full transcript at")
            ?: block.lineAfter("Full transcript:"),
        taskId = block.xmlTag("task_id") ?: block.xmlTag("taskId"),
        subagentAgentId = block.xmlTag("agent_id") ?: block.xmlTag("agentId"),
    )
}

private fun parseJsonObject(raw: String): JsonObject? =
    runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()

private fun JsonObject.stringField(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun String.xmlTag(name: String): String? {
    val content = Regex("<$name(?:\\s[^>]*)?>([\\s\\S]*?)</$name>", RegexOption.IGNORE_CASE)
        .find(this)?.groupValues?.getOrNull(1) ?: return null
    return content.removePrefix("<![CDATA[").removeSuffix("]]>")
        .decodeXmlEntities().trim().takeIf { it.isNotBlank() }
}

private fun String.lineAfter(marker: String): String? {
    val index = indexOf(marker, ignoreCase = true)
    if (index < 0) return null
    val start = index + marker.length
    val end = indexOf('\n', start).let { if (it < 0) length else it }
    return substring(start, end).trim().trimStart(':').trim()
        .decodeXmlEntities().takeIf { it.isNotBlank() }
}

private fun String.decodeXmlEntities(): String =
    replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
