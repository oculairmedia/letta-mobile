package com.letta.mobile.data.meridian.endpoint

import com.letta.mobile.data.controller.capability.Capability
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.extras.HostExternalTool
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A fake runtime stream: the App Server frames letta-code sends around a client-side tool call. */
internal object FakeRuntimeStream {
    private var seq = 0L

    fun scope(agent: String = "agent-a", conversation: String = "conv-a") = AppServerRuntimeScope(agent, conversation)

    fun toolStart(
        scope: AppServerRuntimeScope,
        toolCallId: String,
        command: String,
        toolName: String = "Bash",
        argsAsString: Boolean = false,
        subagentId: String? = null,
    ): AppServerReceivedFrame {
        val args = buildJsonObject { put("command", command) }
        return delta(scope, subagentId) {
            put("message_type", "client_tool_start")
            put("tool_call_id", toolCallId)
            put("tool_name", toolName)
            put("tool_args", if (argsAsString) JsonPrimitive(args.toString()) else args)
        }
    }

    fun toolEnd(scope: AppServerRuntimeScope, toolCallId: String) = delta(scope) {
        put("message_type", "client_tool_end")
        put("tool_call_id", toolCallId)
        put("status", "success")
    }

    fun toolReturn(scope: AppServerRuntimeScope, toolCallId: String) = delta(scope) {
        put("message_type", "tool_return_message")
        put("tool_call_id", toolCallId)
        put("status", "success")
        put("tool_return", "ok")
    }

    fun turnFinished(scope: AppServerRuntimeScope) = AppServerReceivedFrame(
        channel = AppServerChannel.Stream,
        frame = AppServerInboundFrame.TurnFinished(
            runtime = scope,
            eventSeq = ++seq,
            emittedAt = "2026-10-10T00:00:00Z",
            idempotencyKey = "turn_finished:$seq",
            turnId = "turn-$seq",
            stopReason = "end_turn",
        ),
        raw = JsonObject(emptyMap()),
    )

    private fun delta(
        scope: AppServerRuntimeScope,
        subagentId: String? = null,
        body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): AppServerReceivedFrame = AppServerReceivedFrame(
        channel = AppServerChannel.Stream,
        frame = AppServerInboundFrame.StreamDelta(
            runtime = scope,
            eventSeq = ++seq,
            emittedAt = "2026-10-10T00:00:00Z",
            idempotencyKey = "stream_delta:$seq",
            delta = buildJsonObject(body),
            subagentId = subagentId,
        ),
        raw = JsonObject(emptyMap()),
    )
}

/** A `canvas_list` stand-in that answers with the caller scope it was run with. */
internal class CallerEchoTool(override val name: String = "canvas_list") : HostExternalTool {
    val callers = mutableListOf<ExternalToolCaller>()
    override val description = "List canvases."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(emptyMap<String, JsonElement>()))
    }
    override val capability = Capability.ImageHydration

    override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult =
        invoke(input, ExternalToolCaller(agentId))

    override suspend fun invoke(input: JsonObject, caller: ExternalToolCaller): ExternalToolResult {
        callers += caller
        return ExternalToolResult.Success(
            buildJsonObject {
                put("agent", caller.agentId)
                put("conversation", caller.conversationId)
                put("call", caller.toolCallId)
            }.toString(),
        )
    }
}
