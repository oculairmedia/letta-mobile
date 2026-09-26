package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.iroh.canonicalToolCalls
import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.ToolExecutionStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * letta-mobile-qygvv.27: how the node turns its engine's structured tool events back into App
 * Server deltas for the phone.
 *
 * The node's engine reports one tool call up to three times: the auto-approved
 * `approval_request_message` (Unrestricted), the `client_tool_start` lifecycle frame and, for a
 * host tool, the `external_tool_call_request`. Its end arrives twice: the `client_tool_end`
 * lifecycle frame and the real `tool_return_message`. The phone must get ONE `tool_call_message`
 * per call, with its arguments as the JSON object text, and ONE `tool_return_message` carrying the
 * real result. The lifecycle frames go out as what they are, `client_tool_start` /
 * `client_tool_end`: the phone's tool activity signals (run phase), never fake tool rows.
 */
internal class RelayedToolProjection {
    private val announced = HashSet<String>()
    private val started = HashSet<String>()
    private val ended = HashSet<String>()

    /** Records the calls a relayed App Server delta already announced to the phone. */
    fun noteRelayed(delta: JsonObject) {
        val type = (delta["message_type"] as? JsonPrimitive)?.contentOrNull
        if (type == "tool_call_message" || type == "approval_request_message") {
            canonicalToolCalls(delta).forEach { announced += it.toolCallId }
        }
    }

    /**
     * The delta for an observed tool call, or null when the phone already has everything it says.
     * One without arguments (the `client_tool_start` shape) is the tool starting, not a call; a
     * call the phone already has (the host tool's `external_tool_call_request`) is not re-announced.
     */
    fun toolCall(payload: RuntimeEventPayload.ToolCallObserved, runId: RunId?): JsonObject? {
        val id = payload.toolCallId.value
        if (payload.argumentsJson == null) return toolStart(payload, runId)
        if (!announced.add(id)) return null
        return buildJsonObject {
            put("message_type", "tool_call_message")
            runId?.let { put("run_id", it.value) }
            put(
                "tool_call",
                buildJsonObject {
                    put("tool_call_id", id)
                    put("name", payload.toolName.value)
                    put("arguments", decodedArguments(payload.argumentsJson))
                },
            )
        }
    }

    /**
     * The delta for an observed tool return. One with a run id came from the App Server's
     * `client_tool_end`; the real `tool_return_message` follows it as its own delta. One without
     * is the engine's synthetic settlement of a dangling call and is the call's only return.
     */
    fun toolReturn(payload: RuntimeEventPayload.ToolReturnObserved, runId: RunId?): JsonObject? {
        val id = payload.toolCallId.value
        if (runId == null && !isLifecycleBody(payload.body)) return toolReturnMessage(payload)
        if (!ended.add(id)) return null
        return buildJsonObject {
            put("message_type", "client_tool_end")
            runId?.let { put("run_id", it.value) }
            put("tool_call_id", id)
            put("status", statusOf(payload.status))
            if (!isLifecycleBody(payload.body)) put("output", payload.body)
        }
    }

    private fun toolStart(payload: RuntimeEventPayload.ToolCallObserved, runId: RunId?): JsonObject? {
        val id = payload.toolCallId.value
        if (!started.add(id)) return null
        return buildJsonObject {
            put("message_type", "client_tool_start")
            runId?.let { put("run_id", it.value) }
            put("tool_call_id", id)
            put("tool_name", payload.toolName.value)
        }
    }

    private fun toolReturnMessage(payload: RuntimeEventPayload.ToolReturnObserved): JsonObject = buildJsonObject {
        put("message_type", "tool_return_message")
        put("tool_call_id", payload.toolCallId.value)
        put("status", statusOf(payload.status))
        put("tool_return", payload.body)
    }

    private fun statusOf(status: ToolExecutionStatus): String =
        if (status == ToolExecutionStatus.Failed) "error" else "success"

    private fun isLifecycleBody(body: String): Boolean =
        (parseOrNull(body) as? JsonObject)?.get("message_type")?.let { (it as? JsonPrimitive)?.contentOrNull } ==
            "client_tool_end"

    internal companion object {
        /**
         * The arguments as JSON object text. An approval's `tool_call.arguments` is itself a JSON
         * string, which the engine hands over still quoted; unwrap it once.
         */
        fun decodedArguments(raw: String?): String {
            val text = raw ?: return "{}"
            val parsed = parseOrNull(text) as? JsonPrimitive ?: return text
            return if (parsed.isString) parsed.content else text
        }

        private fun parseOrNull(text: String) = runCatching { AppServerProtocol.json.parseToJsonElement(text) }.getOrNull()
    }
}
