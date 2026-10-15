package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/*
 * Pure draft and frame-body helpers of AppServerTurnEngine, moved out of the engine unchanged
 * (letta-mobile-by4vm.7) so the engine keeps only what needs its state.
 */

/**
 * Consecutive-failure counter for the engine's frame projection. Reset
 * by any successfully projected frame, so an isolated bad frame never
 * accumulates toward the cap across an otherwise healthy turn.
 */
internal class FrameProjectionErrorBudget {
    private var consecutive = 0

    fun recordSuccess() {
        consecutive = 0
    }

    /** Returns the new consecutive-failure count. */
    fun recordFailure(): Int {
        consecutive += 1
        return consecutive
    }

    companion object {
        const val MAX_CONSECUTIVE = 8
    }
}

/** The terminal a same-conversation, mismatched-scope authoritative terminal ends the turn with. */
internal fun TurnCommand.draftForScopeRejectedTerminal(status: RuntimeRunStatus): RuntimeEventDraft =
    when (status) {
        RuntimeRunStatus.Failed -> failedDraft(
            "Authoritative terminal from same-conversation mismatched agent scope",
        )
        RuntimeRunStatus.Cancelled -> cancelledDraft(
            "Authoritative cancel from same-conversation mismatched agent scope",
        )
        else -> completedDraft(runId = null)
    }

internal fun RuntimeEventDraft.approvalArgumentsPreview(): String? = when (val payload = this.payload) {
    is RuntimeEventPayload.ApprovalRequested -> payload.request.argumentsPreview
    is RuntimeEventPayload.RemoteStreamFrame -> runCatching {
        val raw = AppServerProtocol.json.parseToJsonElement(payload.body).jsonObject
        val delta = raw["delta"]?.jsonObject ?: raw
        (delta["tool_call"] as? JsonObject)?.get("arguments")?.toString()
            ?: delta["arguments"]?.toString()
    }.getOrNull()
    else -> null
}

internal fun TurnCommand.draftFor(
    runId: RunId?,
    payload: RuntimeEventPayload,
): RuntimeEventDraft = RuntimeEventDraft(
    backendId = backendId,
    runtimeId = runtimeId,
    agentId = agentId,
    conversationId = conversationId,
    runId = runId,
    source = RuntimeEventSource.LocalRuntime,
    payload = payload,
)

/**
 * letta-mobile-oqfbj: extract tool_call_id from a RemoteStreamFrame body.
 * Handles tool_call_message, approval_request_message, and tool_return_message frames.
 */
internal fun extractToolCallId(body: String): String? = runCatching {
    val raw = AppServerProtocol.json.parseToJsonElement(body).jsonObject
    val delta = raw["delta"]?.jsonObject ?: raw
    // Try tool_call.tool_call_id first (tool_call_message shape)
    delta["tool_call"]?.jsonObject?.string("tool_call_id")
        // Then direct tool_call_id field (approval_request_message / tool_return_message shape)
        ?: delta.string("tool_call_id")
}.getOrNull()

/** Best-effort exception class name from a terminal reason string. */
internal fun exceptionTypeToken(reason: String?): String? {
    if (reason.isNullOrBlank()) return null
    // Common shapes: "FooException: …", "java.lang.FooException: …", "Error: FooException …"
    val match = Regex("""([A-Za-z_][\w.$]*?(?:Exception|Error|Failure))\b""")
        .find(reason)
    return match?.groupValues?.getOrNull(1)?.take(120)
}
