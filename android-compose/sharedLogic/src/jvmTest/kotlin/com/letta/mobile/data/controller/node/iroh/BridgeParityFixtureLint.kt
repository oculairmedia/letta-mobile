package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerStopReason
import com.letta.mobile.data.transport.appserver.AppServerTurnBoundary
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * letta-mobile-qygvv.14: checks one recorded frame against the App Server 0.32.x protocol
 * (letta-code 0.32.17 `protocol_v2.d.ts`), so a typo in a fixture fails loudly instead of being
 * decoded as Unknown and silently skipped by both runs.
 */
internal object BridgeParityFixtureLint {
    /** Server -> client frames a recorded turn may contain (`WsProtocolMessage`, turn subset). */
    val FRAME_TYPES = setOf(
        "input_accepted",
        "update_loop_status",
        "update_device_status",
        "update_queue",
        "update_subagent_state",
        "stream_delta",
        "turn_finished",
        "control_request",
        "external_tool_call_request",
        "abort_message_response",
        "resume_queue_response",
    )

    /** `StreamDelta`: Letta message deltas plus the listener's lifecycle deltas. */
    val DELTA_MESSAGE_TYPES = setOf(
        "system_message", "user_message", "assistant_message", "reasoning_message", "hidden_reasoning_message",
        "tool_call_message", "tool_return_message", "approval_request_message", "approval_response_message",
        "stop_reason", "usage_statistics", "error_message", "event_message", "summary_message", "ping",
        "approval_classification_end", "client_tool_start", "client_tool_end", "command_start", "command_end",
        "slash_command_start", "slash_command_end", "status", "retry", "loop_error",
    )

    /** `LoopStatus` (loop-status-protocol.d.ts). */
    val LOOP_STATUSES = setOf(
        "SENDING_API_REQUEST", "WAITING_FOR_API_RESPONSE", "RETRYING_API_REQUEST", "PROCESSING_API_RESPONSE",
        "EXECUTING_CLIENT_SIDE_TOOL", "EXECUTING_COMMAND", "WAITING_ON_APPROVAL", "WAITING_ON_INPUT",
    )

    /** Every problem with [line] as a frame of a turn on [runtime]; empty when it is well formed. */
    fun problems(line: String, runtime: JsonElement): List<String> {
        val json = runCatching { AppServerProtocol.json.parseToJsonElement(line).jsonObject }.getOrNull()
            ?: return listOf("not a JSON object")
        val type = json.parityString("type")
        if (type !in FRAME_TYPES) return listOf("unknown frame type $type")
        return listOfNotNull(
            decodeProblem(line),
            runtimeProblem(json, runtime),
            deltaProblem(json),
            loopStatusProblem(json),
            stopReasonProblem(json),
        )
    }

    private fun decodeProblem(line: String): String? = when (val frame = AppServerProtocol.decodeFrame(line).frame) {
        is AppServerInboundFrame.Unknown -> "decodes as Unknown"
        is AppServerInboundFrame.DecodeFailure -> "does not decode: ${frame.diagnostic}"
        else -> null
    }

    private fun runtimeProblem(json: JsonObject, runtime: JsonElement): String? {
        val own = json["runtime"] ?: return null
        return if (own == runtime) null else "runtime $own is not the turn's $runtime"
    }

    private fun deltaProblem(json: JsonObject): String? {
        val delta = json["delta"] as? JsonObject ?: return null
        val messageType = delta.parityString("message_type")
        return if (messageType in DELTA_MESSAGE_TYPES) null else "unknown delta message_type $messageType"
    }

    private fun loopStatusProblem(json: JsonObject): String? {
        val status = (json["loop_status"] as? JsonObject)?.parityString("status") ?: return null
        return if (status in LOOP_STATUSES) null else "unknown loop status $status"
    }

    /** A stop reason the client cannot classify is read as mid-turn, so it is almost always a typo. */
    private fun stopReasonProblem(json: JsonObject): String? {
        val reason = json.parityString("stop_reason") ?: (json["delta"] as? JsonObject)?.parityString("stop_reason")
        if (reason == null || reason == AppServerStopReason.REQUIRES_APPROVAL) return null
        val boundary = AppServerStopReason.boundaryOf(reason)
        return if (boundary == AppServerTurnBoundary.Continuing) "unclassified stop_reason $reason" else null
    }
}
