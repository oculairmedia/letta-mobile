package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a [RuntimeEventPayload.RemoteStreamFrame] actually is, by the App Server's own
 * `message_type` vocabulary.
 *
 * One classification, one place. The turn processor ([TurnDraftProcessor]) needed it to decide
 * when a turn is still alive; the run-phase reducer
 * ([com.letta.mobile.data.presence.RunPhaseReducer]) needs the same answer to decide whether the
 * agent is reasoning, responding or running a tool. Deriving it twice is how the mascot and the
 * timeline end up disagreeing about the same frame.
 */
enum class RuntimeFrameKind {
    REASONING,
    ASSISTANT,
    TOOL_CALL,
    TOOL_RETURN,
    STOP_REASON,
    USAGE,
    ERROR,
    OTHER,
}

/**
 * The `message_type` sets the App Server uses on the wire. `client_tool_*` are the local-runtime
 * spellings of the same two events.
 */
object RuntimeFrameTypes {
    val toolCall: Set<String> = setOf("client_tool_start", "tool_call_message")
    val toolReturn: Set<String> = setOf("client_tool_end", "tool_return_message")
    val reasoning: Set<String> = setOf("reasoning_message", "hidden_reasoning_message")
    val assistant: Set<String> = setOf("assistant_message")
    val stopReason: Set<String> = setOf("stop_reason")
    val usage: Set<String> = setOf("usage_statistics")
    val error: Set<String> = setOf("error_message", "loop_error")
}

/**
 * The frame's `message_type`, taken from the envelope field when the mapper set it and otherwise
 * from the frame body (the App Server nests the real type under `delta` on stream deltas).
 */
fun RuntimeEventPayload.RemoteStreamFrame.resolvedMessageType(): String? =
    messageType ?: frameMessageType(body)

/** True when either the envelope's or the body's `message_type` is in [types]. */
fun RuntimeEventPayload.RemoteStreamFrame.matchesAnyType(types: Set<String>): Boolean =
    listOf(messageType, frameMessageType(body)).any(types::contains)

/** Classify a stream frame by its message kind. */
fun RuntimeEventPayload.RemoteStreamFrame.frameKind(): RuntimeFrameKind = when {
    matchesAnyType(RuntimeFrameTypes.reasoning) -> RuntimeFrameKind.REASONING
    matchesAnyType(RuntimeFrameTypes.assistant) -> RuntimeFrameKind.ASSISTANT
    matchesAnyType(RuntimeFrameTypes.toolCall) -> RuntimeFrameKind.TOOL_CALL
    matchesAnyType(RuntimeFrameTypes.toolReturn) -> RuntimeFrameKind.TOOL_RETURN
    matchesAnyType(RuntimeFrameTypes.stopReason) -> RuntimeFrameKind.STOP_REASON
    matchesAnyType(RuntimeFrameTypes.usage) -> RuntimeFrameKind.USAGE
    matchesAnyType(RuntimeFrameTypes.error) -> RuntimeFrameKind.ERROR
    else -> RuntimeFrameKind.OTHER
}

/** The `message_type` carried inside a raw frame body, if it parses as one. */
fun frameMessageType(body: String): String? = runCatching {
    val raw = AppServerProtocol.json.parseToJsonElement(body).jsonObject
    val delta = raw["delta"]?.jsonObject ?: raw
    delta["message_type"]?.jsonPrimitive?.content
}.getOrNull()
