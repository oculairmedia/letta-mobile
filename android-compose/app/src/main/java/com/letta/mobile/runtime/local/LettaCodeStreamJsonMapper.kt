package com.letta.mobile.runtime.local

import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolApprovalId
import com.letta.mobile.runtime.ToolApprovalRequest
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolName
import com.letta.mobile.runtime.TurnCommand
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Singleton
class LettaCodeStreamJsonMapper @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = true }

    fun mapLine(line: String, command: TurnCommand): List<RuntimeEventDraft> {
        val root = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return emptyList()
        return mapObject(root, command)
    }

    private fun mapObject(root: JsonObject, command: TurnCommand): List<RuntimeEventDraft> {
        return when (root.string("type")) {
            "stream_event" -> {
                val event = root["event"] as? JsonObject ?: return emptyList()
                mapObject(root.withEventMetadata(event), command)
            }
            "message" -> root.messageDraft(command)?.let(::listOf).orEmpty()
            "control_request" -> root.approvalRequestDraft(command)?.let(::listOf).orEmpty()
            "error" -> listOf(
                command.runStatus(
                    status = RuntimeRunStatus.Failed,
                    reason = root.string("message") ?: "LettaCode runtime error.",
                    runId = root.runId(),
                )
            )
            "result" -> listOf(root.resultDraft(command))
            else -> emptyList()
        }
    }

    private fun JsonObject.messageDraft(command: TurnCommand): RuntimeEventDraft? {
        val body = textContent(this["content"])
            ?: string("reasoning")
            ?: string("message")
            ?: string("body")
            ?: return null
        if (body.isBlank()) return null

        val frameId = string("id") ?: string("uuid") ?: "letta-code-${body.hashCode()}"
        return RuntimeEventDraft(
            backendId = command.backendId,
            runtimeId = command.runtimeId,
            agentId = command.agentId,
            conversationId = command.conversationId,
            runId = string("run_id")?.let(::RunId),
            source = RuntimeEventSource.LocalRuntime,
            payload = RuntimeEventPayload.RemoteStreamFrame(
                frameId = frameId,
                messageId = string("message_id") ?: string("id"),
                messageType = string("message_type") ?: string("messageType"),
                body = body,
            ),
        )
    }

    private fun JsonObject.approvalRequestDraft(command: TurnCommand): RuntimeEventDraft? {
        val request = this["request"] as? JsonObject ?: return null
        if (request.string("subtype") != "can_use_tool") return null
        val toolCallId = request.string("tool_call_id")?.takeIf { it.isNotBlank() } ?: return null
        val toolName = request.string("tool_name")?.takeIf { it.isNotBlank() } ?: return null
        val inputPreview = request["input"]?.toString()
        return RuntimeEventDraft(
            backendId = command.backendId,
            runtimeId = command.runtimeId,
            agentId = command.agentId,
            conversationId = command.conversationId,
            runId = string("run_id")?.let(::RunId),
            source = RuntimeEventSource.LocalRuntime,
            payload = RuntimeEventPayload.ApprovalRequested(
                ToolApprovalRequest(
                    approvalId = ToolApprovalId("letta-code:$toolCallId"),
                    callId = ToolCallId(toolCallId),
                    toolName = ToolName(toolName),
                    prompt = "Allow LettaCode to run $toolName?",
                    argumentsPreview = inputPreview,
                )
            ),
        )
    }

    private fun JsonObject.resultDraft(command: TurnCommand): RuntimeEventDraft {
        val subtype = string("subtype")
        val status = when (subtype) {
            "success" -> RuntimeRunStatus.Completed
            "interrupted" -> RuntimeRunStatus.Cancelled
            else -> RuntimeRunStatus.Failed
        }
        val reason = if (status == RuntimeRunStatus.Completed) {
            null
        } else {
            string("stop_reason") ?: string("result") ?: "LettaCode turn ended with $subtype."
        }
        return command.runStatus(status, reason, runId())
    }

    private fun TurnCommand.runStatus(
        status: RuntimeRunStatus,
        reason: String? = null,
        runId: RunId? = null,
    ): RuntimeEventDraft = RuntimeEventDraft(
        backendId = backendId,
        runtimeId = runtimeId,
        agentId = agentId,
        conversationId = conversationId,
        runId = runId,
        source = RuntimeEventSource.LocalRuntime,
        payload = RuntimeEventPayload.RunLifecycleChanged(status = status, reason = reason),
    )

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.runId(): RunId? =
        string("run_id")?.let(::RunId)

    private fun JsonObject.withEventMetadata(event: JsonObject): JsonObject =
        JsonObject(
            filterKeys { key -> key != "event" }
                .toMutableMap()
                .apply { putAll(event) }
        )

    private fun textContent(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonPrimitive -> element.contentOrNull
        is JsonArray -> element.mapNotNull(::textContent).joinToString(separator = "\n").takeIf { it.isNotBlank() }
        is JsonObject -> {
            element.string("text")
                ?: element.string("reasoning")
                ?: element.string("message")
                ?: textContent(element["content"])
        }
    }
}
