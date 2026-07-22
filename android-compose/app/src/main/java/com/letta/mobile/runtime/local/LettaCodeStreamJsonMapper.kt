package com.letta.mobile.runtime.local

import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolExecutionStatus
import com.letta.mobile.runtime.ToolName
import com.letta.mobile.runtime.TurnCommand
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

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
            "message" -> when (root.string("message_type")) {
                // Reduced tool-progress surface (letta-mobile-bm6x2): the
                // local stream announces a tool invocation as an
                // approval_request_message (auto-approved under the
                // unrestricted interim policy); no return frame follows —
                // results are attached from the on-disk transcript by
                // AndroidLettaCodeHeadlessClient at turn end.
                "approval_request_message", "tool_call_message" ->
                    root.toolCallDrafts(command)
                "tool_return_message" -> root.toolReturnDraft(command)?.let(::listOf).orEmpty()
                else -> root.messageDraft(command)?.let(::listOf).orEmpty()
            }
            "control_request" -> emptyList()
            "tool_call", "tool_call_message" -> emptyList()
            "tool_return", "tool_return_message" -> emptyList()
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
                // Streaming deltas: letta.js gives every streamed chunk a
                // unique frame id ("letta-msg-N") but a stable otid per
                // logical message — key on the otid so the timeline reducer
                // merges chunks into one message instead of rendering each
                // delta as its own bubble.
                messageId = string("message_id") ?: string("otid") ?: string("id"),
                messageType = string("message_type") ?: string("messageType"),
                body = body,
            ),
        )
    }

    private fun JsonObject.toolCallDrafts(command: TurnCommand): List<RuntimeEventDraft> {
        val calls = (this["tool_calls"] as? JsonArray)?.filterIsInstance<JsonObject>()
            ?: listOfNotNull(this["tool_call"] as? JsonObject)
        return calls.mapNotNull { call ->
            val callId = call.string("tool_call_id") ?: call.string("id") ?: return@mapNotNull null
            val name = call.string("name") ?: return@mapNotNull null
            if (callId.isBlank() || name.isBlank()) return@mapNotNull null
            RuntimeEventDraft(
                backendId = command.backendId,
                runtimeId = command.runtimeId,
                agentId = command.agentId,
                conversationId = command.conversationId,
                runId = string("run_id")?.let(::RunId),
                source = RuntimeEventSource.LocalRuntime,
                payload = RuntimeEventPayload.ToolCallObserved(
                    toolCallId = ToolCallId(callId),
                    toolName = ToolName(name),
                    argumentsJson = call.string("arguments"),
                ),
            )
        }
    }

    private fun JsonObject.toolReturnDraft(command: TurnCommand): RuntimeEventDraft? {
        val callId = string("tool_call_id")
            ?: (this["tool_return"] as? JsonObject)?.string("tool_call_id")
            ?: return null
        if (callId.isBlank()) return null
        val body = textContent(this["tool_return"]) ?: textContent(this["content"]) ?: ""
        // is_err may arrive as a JSON boolean (true) or a string ("true").
        // booleanOrNull handles the former; content the latter — cover both.
        val isErrPrimitive = this["is_err"] as? JsonPrimitive
        val isError = string("status") == "error" ||
            isErrPrimitive?.booleanOrNull == true ||
            isErrPrimitive?.contentOrNull == "true"
        return RuntimeEventDraft(
            backendId = command.backendId,
            runtimeId = command.runtimeId,
            agentId = command.agentId,
            conversationId = command.conversationId,
            runId = string("run_id")?.let(::RunId),
            source = RuntimeEventSource.LocalRuntime,
            payload = RuntimeEventPayload.ToolReturnObserved(
                toolCallId = ToolCallId(callId),
                status = if (isError) ToolExecutionStatus.Failed else ToolExecutionStatus.Succeeded,
                body = body,
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
