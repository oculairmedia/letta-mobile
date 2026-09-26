package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerStopReason
import com.letta.mobile.data.transport.appserver.AppServerTurnBoundary
import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/*
 * letta-mobile-qygvv.28: the terminal delta the node writes for an engine lifecycle when the App
 * Server's own frames did not deliver one, and which deltas end a run on the phone.
 */

/** How a relayed engine terminal reaches the phone. */
internal enum class RelayedTerminalShape { Completion, Failure, Cancel }

/** The shape of [payload]'s terminal, or null when it is not a terminal lifecycle. */
internal fun relayedTerminalShape(payload: RuntimeEventPayload.RunLifecycleChanged): RelayedTerminalShape? =
    when (payload.status) {
        RuntimeRunStatus.Completed -> RelayedTerminalShape.Completion
        RuntimeRunStatus.Failed -> RelayedTerminalShape.Failure
        RuntimeRunStatus.Cancelled -> RelayedTerminalShape.Cancel
        else -> null
    }

/**
 * The delta for [payload]'s terminal on [runId]. It carries the run id, and a cancel stays a cancel
 * (`stop_reason cancelled` with the reason as its message; the phone's mapper reads it back) instead
 * of an `error_message` the phone would end as Failed.
 */
internal fun relayedTerminalDelta(payload: RuntimeEventPayload.RunLifecycleChanged, runId: RunId?): JsonObject? =
    when (relayedTerminalShape(payload)) {
        RelayedTerminalShape.Completion -> terminalDelta(STOP_REASON, runId) { put(STOP_REASON, payload.reason ?: "end_turn") }
        RelayedTerminalShape.Failure -> terminalDelta("error_message", runId) { put("message", payload.reason ?: "turn failed") }
        RelayedTerminalShape.Cancel -> terminalDelta(STOP_REASON, runId) {
            put(STOP_REASON, "cancelled")
            put("message", payload.reason ?: "turn cancelled")
        }
        null -> null
    }

/** A delta the phone ends a run on: a terminal-boundary stop_reason, an error, a terminal loop_error. */
internal fun deltaEndsRun(delta: JsonObject): Boolean = when (delta.wireString("message_type")) {
    STOP_REASON -> AppServerStopReason.boundaryOf(delta.wireString(STOP_REASON)) !in OPEN_BOUNDARIES
    "error_message" -> true
    "loop_error" -> delta.wireString("is_terminal") != "false"
    else -> false
}

private const val STOP_REASON = "stop_reason"

private val OPEN_BOUNDARIES = setOf(AppServerTurnBoundary.AwaitingApproval, AppServerTurnBoundary.Continuing)

private fun terminalDelta(type: String, runId: RunId?, fields: JsonObjectBuilder.() -> Unit): JsonObject =
    buildJsonObject {
        put("message_type", type)
        runId?.let { put("run_id", it.value) }
        fields()
    }

private fun JsonObject.wireString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
