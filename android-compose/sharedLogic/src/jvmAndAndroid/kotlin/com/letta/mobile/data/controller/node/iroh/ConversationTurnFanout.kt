package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolExecutionStatus
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * eaczz.4 — the fanout core. Owns a single turn's per-connection frame-shaping
 * state (cumulative assistant text + open-tool_call tracking + terminal-dedup)
 * and fans EACH already-cumulated+tagged wire DELTA BODY out to EVERY viewer of
 * the turn's conversation — not just the initiator.
 *
 * Design (uniform initiator-is-just-a-viewer):
 *  - The delta body is computed ONCE, initiator-side, preserving the exact
 *    [CumulativeStreamText] accumulation, [OpenToolCallTracker] observation,
 *    cm-stream optimistic-dedup tagging, and terminal-duplicate-skip semantics
 *    the single-connection path had.
 *  - That one body is then published to [ConnectionRegistry.viewersFor] and each
 *    [ViewerHandle] (including the initiator's selfViewer) re-wraps it with ITS
 *    OWN monotonic event_seq + fresh idempotency_key via
 *    [IrohViewerHandle.writeBroadcastFrame], under ITS OWN write mutex — so
 *    per-viewer ordering + seq monotonicity + frame-part capability gating stay
 *    intact and shape parity holds.
 *  - Fanout is best-effort per viewer: a slow/dead observer that fails its write
 *    never breaks the loop or the initiator's turn (eaczz.6).
 *
 * CRITICAL ISOLATION: mid-turn redial PARKING is INITIATOR-scoped. Only the
 * initiator's frames are tracked for parking (via [trackInitiatorFrame]); a
 * failing observer is never parked. Observers reconcile via message.list on
 * reconnect (eaczz.6), so they get no parking here.
 *
 * When no registry is present (legacy/test constructions) the fanout still
 * writes to the single [initiatorViewer], so the pre-fanout single-connection
 * behavior is preserved byte-for-shape.
 */
internal class ConversationTurnFanout(
    private val conversationId: String,
    private val runtime: AppServerRuntimeScope,
    private val remoteEndpointId: String,
    /** Snapshot source of every viewer of [conversationId] (incl. the initiator). */
    private val viewersFor: suspend (conversationId: String) -> Set<ViewerHandle>,
    /**
     * The initiator's own viewer handle. Used as the sole fanout target when
     * there is no registry (legacy path), and — importantly — the identity whose
     * frames are tracked for INITIATOR-ONLY redial parking.
     */
    private val initiatorViewer: ViewerHandle?,
    /**
     * Initiator-only parking hook: records a delta body JSON so a mid-turn
     * redial can replay it. MUST NOT run per-observer (parking is initiator
     * scoped — q71yi). No-op when the turn is not parkable (no client_message_id).
     */
    private val trackInitiatorFrame: (deltaJson: String) -> Unit = {},
) {
    private val openToolCalls = OpenToolCallTracker()
    private val cumulativeText = CumulativeStreamText()
    private var terminalWritten = false

    /** Whether a terminal frame has already been broadcast for this turn. */
    val anyTerminalWritten: Boolean get() = terminalWritten

    /** True when this payload is a failure/cancel lifecycle (needs dangling flush first). */
    fun isFailureOrCancelLifecycle(payload: RuntimeEventPayload): Boolean =
        payload is RuntimeEventPayload.RunLifecycleChanged &&
            (payload.status == RuntimeRunStatus.Failed || payload.status == RuntimeRunStatus.Cancelled)

    /** True when this payload is any terminal lifecycle (for terminal-duplicate-skip). */
    fun isTerminalLifecycle(payload: RuntimeEventPayload): Boolean =
        payload is RuntimeEventPayload.RunLifecycleChanged &&
            (payload.status == RuntimeRunStatus.Completed ||
                payload.status == RuntimeRunStatus.Failed ||
                payload.status == RuntimeRunStatus.Cancelled)

    /**
     * Map one runtime-event [payload] to its cumulated + tagged wire delta body
     * and fan it out to every viewer. Returns true when a terminal was broadcast
     * (so the caller can flip its terminalWritten guard). Mirrors the pre-fanout
     * `writeDraftAsStreamDelta` EXACTLY on the delta-body it produces.
     */
    suspend fun onDraft(payload: RuntimeEventPayload): Boolean {
        return when (payload) {
            is RuntimeEventPayload.RemoteStreamFrame -> emitRawFrameBody(payload.body)
            is RuntimeEventPayload.ExternalTransportFrame -> emitRawFrameBody(payload.body)
            is RuntimeEventPayload.ToolCallObserved -> {
                val delta = buildJsonObject {
                    put("message_type", "tool_call_message")
                    put("tool_call", buildJsonObject {
                        put("tool_call_id", payload.toolCallId.value)
                        put("name", payload.toolName.value)
                        put("arguments", payload.argumentsJson ?: "{}")
                    })
                }
                openToolCalls.observe(delta.toString())
                broadcastDeltaBody(delta)
                false
            }
            is RuntimeEventPayload.ToolReturnObserved -> {
                val delta = buildJsonObject {
                    put("message_type", "tool_return_message")
                    put("tool_call_id", payload.toolCallId.value)
                    put("status", if (payload.status == ToolExecutionStatus.Failed) "error" else "success")
                    put("tool_return", payload.body)
                }
                openToolCalls.observe(delta.toString())
                broadcastDeltaBody(delta)
                false
            }
            is RuntimeEventPayload.RunLifecycleChanged -> when (payload.status) {
                RuntimeRunStatus.Completed -> {
                    broadcastDeltaBody(buildJsonObject {
                        put("message_type", "stop_reason")
                        put("stop_reason", payload.reason ?: "end_turn")
                    })
                    terminalWritten = true
                    true
                }
                RuntimeRunStatus.Failed -> {
                    broadcastDeltaBody(buildJsonObject {
                        put("message_type", "error_message")
                        put("message", payload.reason ?: "turn failed")
                    })
                    terminalWritten = true
                    true
                }
                RuntimeRunStatus.Cancelled -> {
                    broadcastDeltaBody(buildJsonObject {
                        put("message_type", "error_message")
                        put("message", payload.reason ?: "turn cancelled")
                        put("status", "cancelled")
                    })
                    terminalWritten = true
                    true
                }
                else -> false
            }
            else -> false
        }
    }

    /**
     * RemoteStreamFrame / ExternalTransportFrame path: the [body] is the FULL
     * upstream wire frame ({type,runtime,event_seq,...,delta}). Preserve the
     * exact single-connection shaping — observe open tool_calls, apply cumulative
     * assistant-text accumulation, cm-stream optimistic-dedup tag the inner delta
     * — then extract that inner delta body and fan IT out. Each viewer re-wraps
     * the delta with its own event_seq + idempotency_key (uniform with the
     * synthesized paths), so the initiator's per-connection monotonic event_seq
     * semantics match what the synthesized `writeStreamDelta` produced.
     */
    private suspend fun emitRawFrameBody(body: String): Boolean {
        openToolCalls.observe(body)
        val cumulated = cumulativeText.applyToRawFrame(body)
        val delta = innerDeltaOf(cumulated)
        if (delta == null) {
            // Not a stream_delta we can re-frame (e.g. usage_statistics-only or a
            // malformed body) — nothing to fan out; not a terminal.
            return false
        }
        val tagged = tagStreamDeltaForOptimisticDedup(delta)
        broadcastDeltaBody(tagged)
        return deltaIsTerminal(tagged)
    }

    /** Flush a synthetic cancelled tool_return for every still-open tool_call (8s45p), fanned out. */
    suspend fun flushOpenToolCalls() {
        val openIds = openToolCalls.openIds()
        if (openIds.isEmpty()) return
        Telemetry.event(
            "IrohNode", "stream.dangling_tool_calls_synthesized",
            "remoteEndpointId" to remoteEndpointId,
            "count" to openIds.size,
        )
        openIds.forEach { toolCallId ->
            broadcastDeltaBody(DanglingToolCallSynthesizer.cancelledToolReturnDelta(toolCallId))
            openToolCalls.close(toolCallId)
        }
    }

    /** Error-terminal path: fan out an error_message terminal delta to every viewer. */
    suspend fun emitErrorTerminal(message: String) {
        broadcastDeltaBody(buildJsonObject {
            put("message_type", "error_message")
            put("message", message)
        })
        terminalWritten = true
    }

    /**
     * The heart of the fanout: tag-preserving publish of ONE delta body to every
     * viewer of the conversation. The body is already cumulated + cm-stream
     * tagged; viewers do NOT re-accumulate or re-tag. Best-effort per viewer — a
     * failing observer never breaks the loop or the turn. Initiator-only parking
     * is recorded ONCE here (not per-viewer).
     */
    private suspend fun broadcastDeltaBody(delta: JsonObject) {
        // Initiator-only redial parking (q71yi): record the untagged-equivalent
        // delta JSON once, matching the pre-fanout writeStreamDelta which tracked
        // the delta it was handed. Never runs per-observer.
        trackInitiatorFrame(delta.toString())
        val viewers = snapshotViewers()
        viewers.forEach { viewer ->
            runCatching { writeToViewer(viewer, delta) }
        }
    }

    private suspend fun snapshotViewers(): Set<ViewerHandle> {
        val fromRegistry = viewersFor(conversationId)
        if (fromRegistry.isNotEmpty()) return fromRegistry
        // Legacy / no-registry path: fan out to just the initiator so the
        // single-connection behavior is preserved when no registry is wired.
        return initiatorViewer?.let { setOf(it) } ?: emptySet()
    }

    private suspend fun writeToViewer(viewer: ViewerHandle, delta: JsonObject) {
        if (viewer is IrohViewerHandle) {
            viewer.writeBroadcastFrame(runtime, delta)
        } else {
            // Non-Iroh viewer (test fakes): re-wrap minimally and hand it a frame.
            viewer.writeFrame(
                buildJsonObject {
                    put("type", "stream_delta")
                    put("runtime", AppServerProtocol.json.encodeToJsonElement(AppServerRuntimeScope.serializer(), runtime))
                    put("delta", delta)
                }.toString(),
            )
        }
    }

    private fun innerDeltaOf(rawFrame: String): JsonObject? = runCatching {
        val obj = AppServerProtocol.json.parseToJsonElement(rawFrame).jsonObject
        if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "stream_delta") return@runCatching null
        obj["delta"]?.jsonObject
    }.getOrNull()

    private fun deltaIsTerminal(delta: JsonObject): Boolean =
        when ((delta["message_type"] as? JsonPrimitive)?.contentOrNull) {
            "stop_reason", "loop_error", "error_message" -> true
            else -> false
        }
}
