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
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One client-side tool call as letta-code announces it: id, tool, args and where it runs. */
internal data class ShellCall(
    val id: String,
    val command: String,
    val toolName: String = "Bash",
    val argsAsString: Boolean = false,
    val subagentId: String? = null,
    val scope: AppServerRuntimeScope = FakeRuntimeStream.SCOPE,
)

/** A fake runtime stream: the App Server frames letta-code sends around a client-side tool call. */
internal object FakeRuntimeStream {
    val SCOPE = AppServerRuntimeScope("agent-a", "conv-a")
    val OTHER_CONVERSATION = AppServerRuntimeScope("agent-a", "conv-b")

    private var seq = 0L

    fun started(call: ShellCall): AppServerReceivedFrame {
        val args = buildJsonObject { put("command", call.command) }
        return delta(call.scope, call.subagentId) {
            put("message_type", "client_tool_start")
            put("tool_call_id", call.id)
            put("tool_name", call.toolName)
            put("tool_args", if (call.argsAsString) JsonPrimitive(args.toString()) else args)
        }
    }

    fun ended(call: ShellCall) = delta(call.scope, subagentId = null) {
        put("message_type", "client_tool_end")
        put("tool_call_id", call.id)
        put("status", "success")
    }

    fun returned(call: ShellCall) = delta(call.scope, subagentId = null) {
        put("message_type", "tool_return_message")
        put("tool_call_id", call.id)
        put("status", "success")
        put("tool_return", "ok")
    }

    fun turnFinished(scope: AppServerRuntimeScope) = AppServerReceivedFrame(
        channel = AppServerChannel.Stream,
        frame = AppServerInboundFrame.TurnFinished(
            runtime = scope,
            eventSeq = ++seq,
            emittedAt = EMITTED_AT,
            idempotencyKey = "turn_finished:$seq",
            turnId = "turn-$seq",
            stopReason = "end_turn",
        ),
        raw = JsonObject(emptyMap()),
    )

    private fun delta(scope: AppServerRuntimeScope, subagentId: String?, body: JsonObjectBuilder.() -> Unit) =
        AppServerReceivedFrame(
            channel = AppServerChannel.Stream,
            frame = AppServerInboundFrame.StreamDelta(
                runtime = scope,
                eventSeq = ++seq,
                emittedAt = EMITTED_AT,
                idempotencyKey = "stream_delta:$seq",
                delta = buildJsonObject(body),
                subagentId = subagentId,
            ),
            raw = JsonObject(emptyMap()),
        )

    private const val EMITTED_AT = "2026-10-10T00:00:00Z"
}

/** A `canvas_list` stand-in that answers with the caller scope it was run with. */
internal class CallerEchoTool : HostExternalTool {
    val callers = mutableListOf<ExternalToolCaller>()
    override val name = "canvas_list"
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
