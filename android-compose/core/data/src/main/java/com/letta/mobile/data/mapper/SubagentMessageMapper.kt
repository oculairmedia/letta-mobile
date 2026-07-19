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
    val result = returnContent?.let(::parseJsonObject)
    return UiSubagentDispatch(
        toolCallId = toolCallId,
        description = args.subagentDescription(),
        subagentType = args.subagentType(),
        runInBackground = args.runInBackgroundFlag(),
        prompt = args.stringField("prompt").orEmpty(),
        taskId = result?.taskIdField(),
        subagentAgentId = result?.subagentAgentIdField(),
    )
}

internal fun extractSubagentNotification(raw: String): UiSubagentNotification? {
    val block = raw.taskNotificationBlock() ?: return null
    return UiSubagentNotification(
        toolCallId = block.firstXmlTag("tool_call_id", "toolCallId"),
        status = block.notificationStatus(),
        summary = block.xmlTag("summary"),
        result = block.xmlTag("result"),
        usage = block.xmlTag("usage"),
        transcriptUri = block.transcriptUri(),
        taskId = block.firstXmlTag("task_id", "taskId"),
        subagentAgentId = block.firstXmlTag("agent_id", "agentId"),
    )
}

private fun String.firstXmlTag(primary: String, alternate: String): String? =
    xmlTag(primary) ?: xmlTag(alternate)

private fun String.notificationStatus(): String =
    firstXmlTag("status", "state") ?: "completed"

private fun String.transcriptUri(): String? =
    xmlTag("transcript")
        ?: lineAfter("Full transcript at")
        ?: lineAfter("Full transcript:")

private fun parseJsonObject(raw: String): JsonObject? =
    runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()

private fun JsonObject.stringField(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.subagentDescription(): String =
    stringField("description")
        ?: stringField("prompt")?.lineSequence()?.firstOrNull()?.take(96)
        ?: "Subagent"

private fun JsonObject.subagentType(): String =
    stringField("subagent_type") ?: stringField("subagentType") ?: "agent"

private fun JsonObject.runInBackgroundFlag(): Boolean =
    this["run_in_background"]?.jsonPrimitive?.booleanOrNull
        ?: this["runInBackground"]?.jsonPrimitive?.booleanOrNull
        ?: false

private fun JsonObject.taskIdField(): String? =
    stringField("task_id") ?: stringField("taskId")

private fun JsonObject.subagentAgentIdField(): String? =
    stringField("subagent_agent_id")
        ?: stringField("subagentAgentId")
        ?: stringField("agent_id")
        ?: stringField("agentId")
