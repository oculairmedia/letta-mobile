package com.letta.mobile.data.mapper

import com.letta.mobile.data.chat.projection.extractSubagentDispatch
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.GeneratedUiPayload
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.model.UiApprovalDecision
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalResponse
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiGeneratedComponent
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

internal val generatedUiToolNames = setOf(
    "render_summary_card",
    "render_metric_card",
    "render_suggestion_chips",
)

internal fun List<AppMessage>.mapToUiMessages(): List<UiMessage> {
    val returnsByCallId = associateToolReturns()
    val subagentCallsByTaskId = associateSubagentCalls(returnsByCallId)
    val renderedToolCallIds = renderedToolCallIds()
    val foldedApprovals = foldedApprovals(renderedToolCallIds)
    val absorbedResponses = fullyAbsorbedApprovalResponseIds(foldedApprovals, renderedToolCallIds)
    val consumedReturnIds = mutableSetOf<String>()

    return buildList {
        for (message in this@mapToUiMessages) {
            when (message.messageType) {
                MessageType.TOOL_CALL -> {
                    val mapped = message.mapToolCall(message.toolCallId?.let(returnsByCallId::get), foldedApprovals)
                    mapped.consumedReturnId?.let(consumedReturnIds::add)
                    add(mapped.message)
                }
                MessageType.TOOL_RETURN -> if (message.id !in consumedReturnIds) {
                    add(message.mapUnmatchedToolReturn(foldedApprovals))
                }
                MessageType.USER, MessageType.ASSISTANT ->
                    add(message.mapToUiMessage().correlateSubagentNotification(subagentCallsByTaskId))
                MessageType.REASONING, MessageType.APPROVAL_REQUEST -> add(message.mapToUiMessage())
                MessageType.APPROVAL_RESPONSE -> if (
                    message.id !in absorbedResponses && message.hasExplicitApprovalDecision()
                ) {
                    add(message.mapToUiMessage())
                }
            }
        }
    }
}

private fun List<AppMessage>.associateToolReturns(): Map<String, AppMessage> = buildMap {
    for (message in this@associateToolReturns) {
        val callId = message.toolCallId
        if (message.messageType == MessageType.TOOL_RETURN && !callId.isNullOrBlank()) put(callId, message)
    }
}

private fun List<AppMessage>.associateSubagentCalls(
    returnsByCallId: Map<String, AppMessage>,
): Map<String, String> = buildMap {
    for (message in this@associateSubagentCalls) {
        val binding = message.subagentTaskBinding(returnsByCallId) ?: continue
        putIfAbsent(binding.taskId, binding.toolCallId)
    }
}

private data class SubagentTaskBinding(val taskId: String, val toolCallId: String)

private fun AppMessage.subagentTaskBinding(
    returnsByCallId: Map<String, AppMessage>,
): SubagentTaskBinding? {
    if (messageType != MessageType.TOOL_CALL) return null
    if (toolName != "Agent") return null
    val callId = toolCallId?.takeIf(String::isNotBlank) ?: return null
    val taskId = extractSubagentDispatch(callId, content, returnsByCallId[callId]?.content)
        ?.taskId
        ?.takeIf(String::isNotBlank)
        ?: return null
    return SubagentTaskBinding(taskId = taskId, toolCallId = callId)
}

private fun UiMessage.correlateSubagentNotification(
    subagentToolCallByTaskId: Map<String, String>,
): UiMessage {
    val notification = subagentNotification ?: return this
    val correlatedToolCallId = notification.toolCallId?.takeIf(String::isNotBlank)
        ?: notification.taskId?.takeIf(String::isNotBlank)?.let(subagentToolCallByTaskId::get)
    return if (correlatedToolCallId != notification.toolCallId) {
        copy(subagentNotification = notification.copy(toolCallId = correlatedToolCallId))
    } else {
        this
    }
}

internal fun extractSendMessageText(arguments: String, returnContent: String): String {
    return try {
        val valueStart = findQuotedMessageValueStart(arguments) ?: return returnContent
        unescapeJsonStringLiteral(arguments, startIndex = valueStart + 1).ifBlank { returnContent }
    } catch (_: Exception) {
        returnContent
    }
}

private fun findQuotedMessageValueStart(arguments: String): Int? {
    val messageStart = arguments.indexOf("\"message\"")
    if (messageStart < 0) return null
    val colon = arguments.indexOf(':', messageStart)
    if (colon < 0) return null
    val valueStart = arguments.indexOf('"', colon + 1)
    return valueStart.takeIf { it >= 0 }
}

private fun unescapeJsonStringLiteral(source: String, startIndex: Int): String = buildString {
    var index = startIndex
    while (index < source.length) {
        when (val char = source[index]) {
            '\\' if index + 1 < source.length -> {
                appendEscapedJsonChar(source[index + 1])
                index += 2
            }
            '"' -> return@buildString
            else -> {
                append(char)
                index++
            }
        }
    }
}

private fun StringBuilder.appendEscapedJsonChar(escaped: Char) {
    when (escaped) {
        '"' -> append('"')
        '\\' -> append('\\')
        'n' -> append('\n')
        't' -> append('\t')
        else -> {
            append('\\')
            append(escaped)
        }
    }
}

internal fun AppMessage.mapToUiMessage(): UiMessage {
    val notification = when (messageType) {
        MessageType.ASSISTANT, MessageType.USER -> extractSubagentNotification(content)
        else -> null
    }
    val role = if (notification != null) "assistant" else messageType.uiRole()
    val toolCalls = mapStandaloneToolCalls()
    return UiMessage(
        id = id,
        role = role,
        content = if (toolCalls != null || notification != null) "" else content,
        timestamp = date.toString(),
        runId = runId,
        stepId = stepId,
        isPending = isPending,
        isReasoning = messageType == MessageType.REASONING,
        toolCalls = toolCalls,
        generatedUi = generatedUi?.let { UiGeneratedComponent(it.component, it.propsJson, it.fallbackText) },
        approvalRequest = approvalRequest?.let { request ->
            UiApprovalRequest(
                requestId = request.requestId,
                toolCalls = request.toolCalls.map {
                    UiApprovalToolCall(it.toolCallId, it.name, it.arguments)
                },
            )
        },
        approvalResponse = approvalResponse?.let { response ->
            UiApprovalResponse(
                requestId = response.requestId,
                approved = response.approved,
                reason = response.reason,
                approvals = response.approvals.map {
                    UiApprovalDecision(it.toolCallId, it.approved, it.status, it.reason)
                },
            )
        },
        subagentNotification = notification,
        attachments = attachments.map { UiImageAttachment(it.base64, it.mediaType) },
    )
}

private fun MessageType.uiRole(): String = when (this) {
    MessageType.USER -> "user"
    MessageType.ASSISTANT, MessageType.REASONING -> "assistant"
    MessageType.TOOL_CALL, MessageType.TOOL_RETURN -> "tool"
    MessageType.APPROVAL_REQUEST, MessageType.APPROVAL_RESPONSE -> "approval"
}

private fun AppMessage.mapStandaloneToolCalls(): List<UiToolCall>? = when (messageType) {
    MessageType.TOOL_CALL -> listOf(
        UiToolCall(
            name = toolName ?: "tool",
            arguments = content,
            result = null,
            toolCallId = toolCallId,
            subagentDispatch = if (toolName == "Agent") extractSubagentDispatch(toolCallId, content, null) else null,
        ),
    )
    MessageType.TOOL_RETURN -> listOf(
        UiToolCall(
            name = toolName ?: "tool",
            arguments = "",
            result = content.ifBlank { null },
            toolCallId = toolCallId,
            subagentDispatch = if (toolName == "Agent") extractSubagentDispatch(toolCallId, "", content) else null,
        ),
    )
    else -> null
}

internal fun extractGeneratedUi(raw: kotlinx.serialization.json.JsonElement?): GeneratedUiPayload? =
    runCatching {
        val obj = raw as? JsonObject ?: return@runCatching null
        val type = (obj["type"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        if (type != "generated_ui") return@runCatching null
        val component = (obj["component"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: return@runCatching null
        GeneratedUiPayload(
            component = component,
            propsJson = obj["props"]?.toString() ?: buildJsonObject {}.toString(),
            fallbackText = (obj["text"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?: (obj["fallback_text"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
        )
    }.getOrNull()

internal fun extractGeneratedUiFromString(raw: String): GeneratedUiPayload? {
    if (raw.isBlank()) return null
    return runCatching { extractGeneratedUi(Json.parseToJsonElement(raw)) }.getOrNull()
}
