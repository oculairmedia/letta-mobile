package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.runtime.TurnBoundaryGate
import com.letta.mobile.data.runtime.TurnInputAcknowledgement
import com.letta.mobile.data.runtime.TurnInputAcknowledgementListener
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.AppServerStopReason
import com.letta.mobile.data.transport.appserver.AppServerTurnBoundary
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

/*
 * letta-mobile-qygvv.12: App Server protocol parity for turns the Iroh node runs for its clients.
 *
 * The node does not proxy a client's `input` to the App Server: it runs the turn through its own
 * engine and relays the engine's drafts as `stream_delta` frames. Before this, a client therefore
 * never saw `input_accepted`, `update_loop_status`, `update_queue` or `turn_finished`: its ack wait
 * timed out 30s after every send, and it could only end a turn on `stop_reason` plus the 1.5s
 * settle window while the node had already moved on. [IrohRelayedTurnProtocol] rebuilds those
 * frames from what the node's engine learned, in the App Server's own order.
 */

/** Wraps [fields] in the envelope every App Server event frame carries. */
internal fun protocolFrame(
    type: String,
    runtime: AppServerRuntimeScope,
    fields: JsonObject,
    eventSeq: Long?,
): JsonObject = buildJsonObject {
    put("type", type)
    put("runtime", AppServerProtocol.json.encodeToJsonElement(AppServerRuntimeScope.serializer(), runtime))
    if (eventSeq != null) put("event_seq", eventSeq)
    put("emitted_at", Instant.now().toString())
    put("idempotency_key", "iroh-$type-${UUID.randomUUID()}")
    fields.forEach { (key, value) -> put(key, value) }
}

/** The control-channel answer to an `input` that carried [requestId]. */
internal fun inputAcceptedFrame(
    requestId: String,
    runtime: AppServerRuntimeScope,
    ack: TurnInputAcknowledgement,
): String = buildJsonObject {
    put("type", "input_accepted")
    put("request_id", requestId)
    put("runtime", AppServerProtocol.json.encodeToJsonElement(AppServerRuntimeScope.serializer(), runtime))
    put("accepted", ack.accepted)
    ack.disposition?.let { put("disposition", it) }
    ack.error?.let { put("error", it) }
}.toString()

/** How a relayed turn ended, in `turn_finished` terms. */
internal data class RelayedTurnEnd(val stopReason: String, val error: String?)

private val ENVELOPE_KEYS = setOf("type", "runtime", "event_seq", "emitted_at", "idempotency_key")

private const val LOOP_PROCESSING = "PROCESSING_API_RESPONSE"

/**
 * One relayed turn's protocol state for its initiator: the input ack on the control channel, and
 * the loop-status / queue / turn_finished frames on the stream channel. Frames reach the
 * initiator only; observers keep reconciling through message.list.
 */
internal class IrohRelayedTurnProtocol(
    private val runtime: AppServerRuntimeScope,
    private val requestId: String?,
    private val clientMessageId: String?,
    private val fanout: ConversationTurnFanout,
    private val writeControl: suspend (String) -> Unit,
    /** Runs once, right after an accepted ack (the observer user echo). */
    private val afterAccepted: suspend () -> Unit = {},
) {
    private val ackLock = Mutex()
    private var acknowledged = false
    private var runId: String? = null
    private var announcedRunId: String? = null
    private var upstreamStopReason: String? = null
    private var finished = false

    /** Installed around the engine's collect so the engine's acceptance reaches [acknowledge]. */
    val listener = TurnInputAcknowledgementListener { ack -> acknowledge(ack) }

    /** Answers the input once. Later calls are no-ops, so the first known outcome wins. */
    suspend fun acknowledge(ack: TurnInputAcknowledgement) {
        val first = ackLock.withLock { (!acknowledged).also { acknowledged = true } }
        if (!first) return
        Telemetry.event(
            "IrohNode", "input.ack",
            "conversationId" to runtime.conversationId,
            "accepted" to ack.accepted,
            "disposition" to (ack.disposition ?: ""),
            "hasRequestId" to (requestId != null),
        )
        requestId?.let { id -> writeControlSafely(inputAcceptedFrame(id, runtime, ack)) }
        if (ack.accepted) afterAccepted()
    }

    /** Before the fanout forwards [draft]: settle the ack if the engine has not reported it yet. */
    suspend fun beforeDraft(draft: RuntimeEventDraft) {
        draft.runId?.value?.takeIf { it.isNotBlank() }?.let { runId = it }
        val payload = draft.payload
        if (payload is RuntimeEventPayload.RunLifecycleChanged && payload.status == RuntimeRunStatus.Started) return
        val end = turnEndOf(payload)
        // A turn that fails before the engine reported acceptance never ran for the client.
        if (end?.stopReason == STOP_ERROR) acknowledge(TurnInputAcknowledgement.rejected(end.error ?: STOP_ERROR))
        acknowledge(TurnInputAcknowledgement.Started)
    }

    /** After the fanout forwarded [draft]: loop status, queue snapshots and the terminal frames. */
    suspend fun afterDraft(draft: RuntimeEventDraft) {
        when (val payload = draft.payload) {
            is RuntimeEventPayload.RemoteStreamFrame -> if (payload.messageType == "stop_reason") {
                upstreamStopReason = innerStopReason(payload.body) ?: upstreamStopReason
            }
            is RuntimeEventPayload.ExternalTransportFrame -> forwardQueueSnapshot(payload.body)
            is RuntimeEventPayload.RunLifecycleChanged -> onLifecycle(payload, draft)
            else -> Unit
        }
    }

    /** The input will not run (busy rejection of a concurrent send, or a failure before it ran). */
    suspend fun rejectInput(errorText: String) {
        acknowledge(TurnInputAcknowledgement.rejected(errorText))
    }

    /** The turn failed outside the engine's own terminal (exception out of the collector). */
    suspend fun onTurnError(errorText: String) {
        acknowledge(TurnInputAcknowledgement.rejected(errorText))
        finish(RelayedTurnEnd(STOP_ERROR, errorText))
    }

    private suspend fun onLifecycle(payload: RuntimeEventPayload.RunLifecycleChanged, draft: RuntimeEventDraft) {
        if (payload.status == RuntimeRunStatus.Running) {
            draft.runId?.value?.takeIf { it.isNotBlank() }?.let { announceRunning(it) }
            return
        }
        turnEndOf(payload)?.let { finish(it) }
    }

    private fun turnEndOf(payload: RuntimeEventPayload): RelayedTurnEnd? {
        val lifecycle = payload as? RuntimeEventPayload.RunLifecycleChanged ?: return null
        return when (lifecycle.status) {
            RuntimeRunStatus.Completed -> RelayedTurnEnd(
                lifecycle.reason ?: upstreamStopReason?.takeIf(::isCompletedReason) ?: STOP_END_TURN,
                null,
            )
            RuntimeRunStatus.Cancelled -> RelayedTurnEnd(STOP_CANCELLED, lifecycle.reason)
            RuntimeRunStatus.Failed -> RelayedTurnEnd(STOP_ERROR, lifecycle.reason ?: "turn failed")
            else -> null
        }
    }

    /** The run is bound: tell the client which run consumed its input (qygvv.8 on the phone). */
    private suspend fun announceRunning(boundRunId: String) {
        runId = boundRunId
        if (announcedRunId == boundRunId || finished) return
        announcedRunId = boundRunId
        fanout.writeInitiatorProtocolFrame("update_loop_status", loopStatusFields(LOOP_PROCESSING, active = true))
    }

    /** App Server order after the stop_reason delta: idle loop status, then turn_finished. */
    private suspend fun finish(end: RelayedTurnEnd) {
        if (finished) return
        finished = true
        fanout.writeInitiatorProtocolFrame(
            "update_loop_status",
            loopStatusFields(TurnBoundaryGate.LOOP_WAITING_ON_INPUT, active = false),
        )
        val turnFinished = buildJsonObject {
            put("turn_id", runId ?: clientMessageId?.let { "turn-$it" } ?: "turn-${UUID.randomUUID()}")
            put("stop_reason", end.stopReason)
            runId?.let { put("run_id", it) }
            end.error?.let { put("error", it) }
        }
        fanout.writeInitiatorProtocolFrame("turn_finished", turnFinished, drain = true)
        Telemetry.event(
            "IrohNode", "turn.finished_relayed",
            "conversationId" to runtime.conversationId,
            "stopReason" to end.stopReason,
            "runId" to (runId ?: "<none>"),
        )
    }

    private fun loopStatusFields(status: String, active: Boolean): JsonObject {
        val run = runId
        return buildJsonObject {
            put(
                "loop_status",
                buildJsonObject {
                    put("status", status)
                    put("active_run_ids", buildJsonArray { if (active && run != null) add(JsonPrimitive(run)) })
                    put(
                        "client_message_ids_by_run_id",
                        buildJsonObject {
                            if (run != null && clientMessageId != null) {
                                put(run, buildJsonArray { add(JsonPrimitive(clientMessageId)) })
                            }
                        },
                    )
                },
            )
        }
    }

    /** Re-wraps an upstream `update_queue` for this runtime with the initiator's own envelope. */
    private suspend fun forwardQueueSnapshot(body: String) {
        val raw = runCatching { AppServerProtocol.json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return
        if ((raw["type"] as? JsonPrimitive)?.contentOrNull != "update_queue") return
        val fields = JsonObject(raw.filterKeys { it !in ENVELOPE_KEYS })
        fanout.writeInitiatorProtocolFrame("update_queue", fields)
    }

    private suspend fun writeControlSafely(frame: String) {
        try {
            writeControl(frame)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Telemetry.event(
                "IrohNode", "input.ack_write_failed",
                "conversationId" to runtime.conversationId,
                "error" to (error.message ?: error.toString()),
                level = Telemetry.Level.WARN,
            )
        }
    }

    private fun innerStopReason(body: String): String? = runCatching {
        val delta = AppServerProtocol.json.parseToJsonElement(body).jsonObject["delta"]?.jsonObject
        (delta?.get("stop_reason") as? JsonPrimitive)?.contentOrNull
    }.getOrNull()

    private fun isCompletedReason(reason: String): Boolean =
        AppServerStopReason.boundaryOf(reason) == AppServerTurnBoundary.Completed

    private companion object {
        const val STOP_END_TURN = "end_turn"
        const val STOP_CANCELLED = "cancelled"
        const val STOP_ERROR = "error"
    }
}

/**
 * Runs [command] through [controller] and relays every draft to [fanout], with [protocol] adding
 * the App Server frames around them. [onFailure] handles an exception out of the collector.
 */
internal suspend fun relayTurn(
    controller: AppServerController,
    command: TurnCommand,
    fanout: ConversationTurnFanout,
    protocol: IrohRelayedTurnProtocol,
    onFailure: suspend (Throwable) -> Unit,
) {
    runCatching {
        withContext(protocol.listener) {
            controller.runTurn(command).collect { draft -> relayDraft(fanout, protocol, draft) }
        }
    }.onFailure { error -> onFailure(error) }
}

private suspend fun relayDraft(
    fanout: ConversationTurnFanout,
    protocol: IrohRelayedTurnProtocol,
    draft: RuntimeEventDraft,
) {
    val payload = draft.payload
    if (fanout.anyTerminalWritten && fanout.isTerminalLifecycle(payload)) {
        Telemetry.event(
            "IrohNode", "stream.terminal_duplicate_skipped",
            "agentId" to draft.agentId?.value,
            "conversationId" to draft.conversationId?.value,
        )
        return
    }
    protocol.beforeDraft(draft)
    // Before a failure/cancel terminal, close any dangling tool_calls
    // so the client never renders a tool_call without a return.
    if (fanout.isFailureOrCancelLifecycle(payload)) {
        fanout.flushOpenToolCalls()
    }
    fanout.onDraft(payload)
    protocol.afterDraft(draft)
}
