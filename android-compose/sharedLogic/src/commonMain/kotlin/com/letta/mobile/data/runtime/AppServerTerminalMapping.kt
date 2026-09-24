package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerStopReason
import com.letta.mobile.data.transport.appserver.AppServerTurnBoundary
import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * letta-mobile-qygvv.2: the terminal-bearing branches of [AppServerRuntimeEventMapper] —
 * `turn_finished`, recoverable vs terminal error deltas — plus the draft builders they share.
 */

/** One readable `stream_delta`: its frame id, run, decoded delta object, and the raw frame. */
internal data class StreamDeltaRef(
    val frameId: String,
    val runId: RunId?,
    val delta: JsonObject,
    val raw: JsonObject,
)

/**
 * `turn_finished` is the server's authoritative end of a turn. Its stop reason is read exactly
 * like the delta's; `requires_approval` and unknown reasons stay open (null).
 */
internal fun AppServerInboundFrame.TurnFinished.toTerminalDraft(command: TurnCommand): RuntimeEventDraft? {
    val finishedRunId = runId?.takeIf { it.isNotBlank() }?.let(::RunId)
    return when (AppServerStopReason.boundaryOf(stopReason)) {
        AppServerTurnBoundary.AwaitingApproval, AppServerTurnBoundary.Continuing -> null
        AppServerTurnBoundary.Cancelled ->
            command.runLifecycleDraft(RuntimeRunStatus.Cancelled, runId = finishedRunId, reason = error)
        AppServerTurnBoundary.Failed -> command.runLifecycleDraft(
            RuntimeRunStatus.Failed,
            runId = finishedRunId,
            reason = error ?: "App Server turn stopped with $stopReason",
        )
        AppServerTurnBoundary.Completed -> command.runLifecycleDraft(RuntimeRunStatus.Completed, runId = finishedRunId)
    }
}

/**
 * A `loop_error` / `error_message` delta. Only an explicit `is_terminal: false` (0.32
 * LoopErrorMessage) makes it a notice the loop recovers from; absent means terminal (pre-0.32).
 */
internal fun TurnCommand.errorDeltaDraft(ref: StreamDeltaRef, failureReason: String): RuntimeEventDraft =
    if (ref.delta.isNonTerminalError()) {
        remoteStreamFrame(ref)
    } else {
        runLifecycleDraft(RuntimeRunStatus.Failed, runId = ref.runId, reason = failureReason)
    }

/** The delta surfaced as-is, as a remote stream frame on the local runtime. */
internal fun TurnCommand.remoteStreamFrame(ref: StreamDeltaRef): RuntimeEventDraft =
    turnDraft(
        runId = ref.runId,
        source = RuntimeEventSource.LocalRuntime,
        payload = RuntimeEventPayload.RemoteStreamFrame(
            frameId = ref.frameId,
            messageId = ref.delta.wireString("id"),
            messageType = ref.delta.wireString("message_type"),
            body = ref.raw.toString(),
        ),
    )

internal fun TurnCommand.runLifecycleDraft(
    status: RuntimeRunStatus,
    runId: RunId? = null,
    reason: String? = null,
): RuntimeEventDraft =
    turnDraft(
        runId = runId,
        source = RuntimeEventSource.LocalRuntime,
        payload = RuntimeEventPayload.RunLifecycleChanged(
            status = status,
            reason = reason,
        ),
    )

internal fun TurnCommand.turnDraft(
    runId: RunId? = null,
    source: RuntimeEventSource,
    payload: RuntimeEventPayload,
): RuntimeEventDraft =
    RuntimeEventDraft(
        backendId = backendId,
        runtimeId = runtimeId,
        agentId = agentId,
        conversationId = conversationId,
        runId = runId,
        source = source,
        payload = payload,
    )

private fun JsonObject.isNonTerminalError(): Boolean = wireString("is_terminal") == "false"

/** Fail-soft scalar read (letta-mobile-fkpd4): never throws on a non-scalar wire value. */
private fun JsonObject.wireString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
