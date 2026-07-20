package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolExecutionStatus
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

import kotlin.time.Duration.Companion.milliseconds
/**
 * eaczz.4 — the fanout core. Owns a single turn's per-connection frame-shaping
 * state (cumulative assistant text + open-tool_call tracking + terminal-dedup)
 * and fans EACH already-cumulated+tagged wire DELTA BODY out to EVERY viewer of
 * the turn's conversation — not just the initiator.
 *
 * Design (uniform initiator-is-just-a-viewer):
 *  - The delta body is computed ONCE, initiator-side, preserving the exact
 *    [OpenToolCallTracker] observation,
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
 * eaczz.6 — OBSERVER LIFECYCLE / CONVERGENCE:
 *  - JOIN MID-TURN: an observer that subscribes (via its own message.list
 *    hydrate) AFTER the turn started may miss the earliest deltas, but it always
 *    reconciles-to-final. Because [snapshotViewers] re-reads the registry on
 *    EVERY delta, a mid-turn joiner immediately starts receiving the remaining
 *    live deltas; assistant frames are CUMULATIVE snapshots (each delta carries
 *    the full text so far), so the next delta self-heals any gap, and the
 *    terminal + the joiner's own message.list hydrate close the rest. It never
 *    gets initiator parking (parking is initiator-only).
 *  - DISCONNECT MID-TURN: the connection's disconnect path unregisters it
 *    (eaczz.1 unregisterAll); the next [snapshotViewers] no longer contains it,
 *    so the broadcaster stops writing to it — the initiator is unaffected.
 *  - RECONNECT: on redial the observer re-subscribes (message.list) and
 *    reconciles to the same final timeline; it gets NO parking replay.
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
    /**
     * eaczz.6 fault isolation: de-register a failed/wedged OBSERVER viewer from
     * the SAME registry the fanout reads from ([viewersFor]) so the broadcaster
     * stops writing to a dead peer on subsequent deltas. Never invoked for the
     * initiator (which follows the parking path, not de-registration). No-op when
     * there is no registry (legacy/test construction).
     */
    private val unregisterViewer: suspend (conversationId: String, viewer: ViewerHandle) -> Unit = { _, _ -> },
    /**
     * eaczz.6 non-blocking fanout: bounded per-OBSERVER write timeout. A wedged
     * QUIC observer stream that does not complete a write within this window is
     * treated as failed, de-registered, and skipped on later deltas — so one dead
     * peer cannot serially stall the fanout. The INITIATOR viewer is NEVER subject
     * to this timeout (it must receive every frame). Injectable for tests.
     */
    private val observerWriteTimeoutMs: Long = OBSERVER_WRITE_TIMEOUT_MS,
) {
    private val openToolCalls = OpenToolCallTracker()
    private val incrementalText = IncrementalStreamText()
    private val deliveredTextKeysByViewer = mutableMapOf<String, MutableSet<String>>()
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
     * exact single-connection shaping — observe open tool_calls and cm-stream
     * optimistic-dedup tag the inner delta, then fan IT out incrementally. Each viewer re-wraps
     * the delta with its own event_seq + idempotency_key (uniform with the
     * synthesized paths), so the initiator's per-connection monotonic event_seq
     * semantics match what the synthesized `writeStreamDelta` produced.
     */
    private suspend fun emitRawFrameBody(body: String): Boolean {
        openToolCalls.observe(body)
        val delta = innerDeltaOf(body)
        if (delta == null) {
            // Not a stream_delta we can re-frame (e.g. usage_statistics-only or a
            // malformed body) — nothing to fan out; not a terminal.
            return false
        }
        val incremental = incrementalText.applyToDelta(delta) ?: return false
        val fragment = tagStreamDeltaForOptimisticDedup(incremental.fragment)
        val checkpoint = tagStreamDeltaForOptimisticDedup(incremental.checkpoint)
        broadcastDeltaBody(fragment, incremental.streamKey, checkpoint)
        return deltaIsTerminal(fragment)
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
     * eaczz.5 — live user-echo fanout. Synthesize a `user_message` wire delta for
     * the sender's prompt and fan it out to EVERY viewer at turn start, BEFORE
     * any assistant stream. Observers render it as a fresh user row so the prompt
     * appears before the reply; the initiator collapses it against its own
     * optimistic Local row and does NOT double-render.
     *
     * DEDUP / IDEMPOTENCY (see SOP "Timeline reducer replay semantics"): the echo
     * is a SNAPSHOT, not an append. The mobile reducer keys idempotency on the
     * event's `otid` (Timeline.identityKeys -> "otid:<otid>") and its stable
     * server `id`. We carry:
     *  - `otid` == the initiator's [clientMessageId] — the SAME otid the sender's
     *    optimistic Local row already holds. On the initiator, the reducer's
     *    `containsIdentityFor` sees the shared "otid:<clientMessageId>" key and
     *    drops the echo (optimistic + echo collapse to ONE user row).
     *  - a STABLE server `id` derived from that otid (`cm-user-<otid>`) so a
     *    replayed echo shares BOTH identity keys and is never appended twice
     *    (replaying "hello" never yields "hellohello").
     * On an OBSERVER (which has no Local row for this otid) the echo appends once
     * as a user row; a re-delivery of the identical echo is dropped by the same
     * identity-key rule — idempotent on every viewer.
     *
     * Content parts (images) are carried verbatim: when [contentParts] is a JSON
     * array we forward it as `content`; otherwise the plain [text] string.
     *
     * NOT parked: parking (q71yi) replays the INITIATOR's own live turn tail on
     * redial; the user echo is derived deterministically from the resent input
     * and the reconnecting initiator re-owns its optimistic row, so echoing it is
     * pure fanout — it must NOT count toward initiator parking (which stays
     * scoped to assistant/tool/terminal deltas). Uses [broadcastDeltaBodyNoPark].
     */
    suspend fun broadcastUserEcho(
        clientMessageId: String,
        text: String,
        contentParts: JsonElement?,
    ) {
        val content: JsonElement = contentParts ?: JsonPrimitive(text)
        val delta = buildJsonObject {
            put("message_type", "user_message")
            // Stable, idempotent server id keyed on the sender otid.
            put("id", "cm-user-$clientMessageId")
            // Same otid as the initiator's optimistic Local row => collapse.
            put("otid", clientMessageId)
            // Stable seq_id so the reducer treats a re-delivery of this SAME
            // frame as a snapshot (EQUAL branch), never an append — otherwise
            // replaying "hello" would concatenate to "hellohello". A user echo
            // is a single, complete snapshot, so a constant seq is correct.
            put("seq_id", USER_ECHO_SEQ_ID)
            put("content", content)
        }
        broadcastDeltaBodyNoPark(delta)
    }

    /**
     * The heart of the fanout: tag-preserving publish of ONE delta body to every
     * viewer of the conversation. The body is already cm-stream tagged; viewers
     * do not re-tag or otherwise rewrite it. Best-effort per viewer — a
     * failing observer never breaks the loop or the turn. Initiator-only parking
     * is recorded ONCE here (not per-viewer).
     */
    private suspend fun broadcastDeltaBody(
        delta: JsonObject,
        streamKey: String = "",
        checkpoint: JsonObject = delta,
    ) {
        // Initiator-only redial parking (q71yi): record the untagged-equivalent
        // delta JSON once, matching the pre-fanout writeStreamDelta which tracked
        // the delta it was handed. Never runs per-observer.
        trackInitiatorFrame(delta.toString())
        broadcastDeltaBodyNoPark(delta, streamKey, checkpoint)
    }

    /**
     * Publish ONE delta body to every viewer WITHOUT recording an initiator
     * parking entry. Used by the user-echo path, which is derived
     * deterministically from the (redial-resent) input rather than from live
     * turn frames — so it must not consume a parking slot.
     *
     * eaczz.6 — NON-BLOCKING FANOUT + FAULT ISOLATION. Every viewer is written
     * CONCURRENTLY inside a [supervisorScope]: a per-viewer failure (thrown
     * exception, false-return, or observer timeout) can NEVER cancel a sibling
     * write or propagate to the caller's collect loop / controller.runTurn — the
     * initiator turn always completes.
     *
     * Concurrency (not per-viewer serial) is the chosen non-blocking strategy:
     * a wedged QUIC observer stream cannot serially-block the others because
     * writes proceed in parallel. It is ALSO bounded by [observerWriteTimeoutMs]
     * for OBSERVERS only — a dead peer that never completes a write is timed out,
     * de-registered on the FIRST stall, and skipped on every subsequent delta, so
     * the total delay it can add to the turn is at most one timeout window (a
     * bound), not one-per-delta. Tradeoff vs. a pure fire-and-forget scheme: we
     * join each delta before the next so per-viewer frame ORDERING + event_seq
     * monotonicity are preserved (a viewer's mutex still serializes its writes),
     * at the cost of that single bounded wait; ordering correctness is worth it.
     *
     * The INITIATOR viewer is written with NO timeout and its failure is NOT
     * de-registered here — it must receive EVERY frame, and its stream death is
     * handled by the existing parking path in [IrohNodeConnection]. Timeout +
     * drop is observer-only.
     */
    private suspend fun broadcastDeltaBodyNoPark(
        delta: JsonObject,
        streamKey: String = "",
        checkpoint: JsonObject = delta,
    ) {
        val viewers = snapshotViewers()
        // eaczz observability: the fanout write path emits no stream.write
        // telemetry, so multi-client delivery was invisible in the wrapper log.
        // Log the viewer count + ids per broadcast so a turn reaching every
        // viewer is greppable (fanout.broadcast).
        Telemetry.event(
            "IrohNode", "fanout.broadcast",
            "conversationId" to conversationId,
            "viewerCount" to viewers.size,
            "viewerIds" to viewers.joinToString(",") { it.connectionId.take(12) },
            "initiatorId" to (initiatorViewer?.connectionId?.take(12) ?: "none"),
        )
        if (viewers.isEmpty()) return
        supervisorScope {
            viewers.map { viewer ->
                async {
                    val isInitiator = viewer === initiatorViewer ||
                        (initiatorViewer != null && viewer.connectionId == initiatorViewer.connectionId)
                    val viewerKeys = deliveredTextKeysByViewer.getOrPut(viewer.connectionId) { mutableSetOf() }
                    val viewerDelta = if (streamKey.isNotEmpty() && viewerKeys.add(streamKey)) checkpoint else delta
                    writeToViewerIsolated(viewer, viewerDelta, isInitiator)
                }
            }.awaitAll()
        }
    }

    /**
     * Write [delta] to one viewer with full fault isolation. Returns Unit; all
     * failure is absorbed. For OBSERVERS, a write that fails, returns false, or
     * exceeds [observerWriteTimeoutMs] causes de-registration via
     * [unregisterViewer] (through the same registry the fanout reads from) so the
     * broadcaster stops writing to it on later deltas. For the INITIATOR, no
     * timeout is applied and no de-registration occurs.
     */
    private suspend fun writeToViewerIsolated(
        viewer: ViewerHandle,
        delta: JsonObject,
        isInitiator: Boolean,
    ) {
        val ok: Boolean? = try {
            if (isInitiator) {
                writeToViewer(viewer, delta)
            } else {
                // Observer: bounded so a wedged stream cannot stall the fanout.
                withTimeoutOrNull(observerWriteTimeoutMs.milliseconds) { writeToViewer(viewer, delta) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (isInitiator) return
        // Observer: null = timed out, false = write failed. Either way drop it.
        if (ok != true) {
            Telemetry.event(
                "IrohNode", "fanout.observer_dropped",
                "remoteEndpointId" to remoteEndpointId,
                "connectionId" to viewer.connectionId,
                "conversationId" to conversationId,
                "reason" to if (ok == null) "write_timeout" else "write_failed",
                level = Telemetry.Level.WARN,
            )
            runCatching { unregisterViewer(conversationId, viewer) }
        }
    }

    private suspend fun snapshotViewers(): Set<ViewerHandle> {
        val fromRegistry = viewersFor(conversationId)
        if (fromRegistry.isNotEmpty()) return fromRegistry
        // Legacy / no-registry path: fan out to just the initiator so the
        // single-connection behavior is preserved when no registry is wired.
        return initiatorViewer?.let { setOf(it) } ?: emptySet()
    }

    private suspend fun writeToViewer(viewer: ViewerHandle, delta: JsonObject): Boolean {
        return if (viewer is IrohViewerHandle) {
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

    private companion object {
        // Stable seq for the user echo so a re-delivery is a snapshot, not an
        // append (see [broadcastUserEcho]). Zero cannot collide with an assistant
        // row (distinct serverId + message_type), so the reducer never conflates
        // the two.
        const val USER_ECHO_SEQ_ID = 0

        // eaczz.6: bound one OBSERVER write so a wedged QUIC stream cannot stall
        // the fanout. Generous enough for a healthy remote peer's writeAll to
        // complete; a peer that exceeds it is treated as dead and de-registered.
        // The INITIATOR is never subject to this — it must get every frame.
        const val OBSERVER_WRITE_TIMEOUT_MS = 5_000L
    }
}
