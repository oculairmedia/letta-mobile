package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.util.Telemetry
import computer.iroh.SendStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

/**
 * Minimal byte sink for a viewer's wire stream. Abstracts [computer.iroh.SendStream]
 * (a JNI class that cannot be faked) so [IrohViewerHandle] is unit-testable with a
 * capturing fake. The real adapter wraps a SendStream ([sendStreamSink]).
 */
interface ViewerFrameSink {
    suspend fun writeAll(bytes: ByteArray)
}

/** Adapt a real Iroh [SendStream] to a [ViewerFrameSink]. */
fun sendStreamSink(send: SendStream): ViewerFrameSink = object : ViewerFrameSink {
    override suspend fun writeAll(bytes: ByteArray) = send.writeAll(bytes)
}

/**
 * eaczz.2 — a per-connection viewer of a conversation's live turn stream.
 *
 * The h30cy frame-shape contract is stateful PER TURN PER CONNECTION: assistant
 * text is accumulated cumulatively and its id rewritten to `cm-stream-<otid>`;
 * each wire frame carries a per-connection monotonic `event_seq` and a fresh
 * `idempotency_key`; frame-part chunking is gated on the peer's advertised
 * capability. For multi-client fanout the initiator computes the
 * cumulated+tagged assistant delta ONCE (idempotent accumulation), and every
 * viewer — including the initiator — re-wraps that delta body with ITS OWN
 * event_seq + idempotency_key and writes to ITS OWN stream under ITS OWN mutex,
 * honoring ITS OWN frame-part capability. This keeps per-connection seq
 * monotonicity and capability gating intact while guaranteeing shape parity.
 *
 * [writeFrame] (raw already-encoded wire frame), [writeBroadcastFrame] (a tagged
 * delta body re-wrapped per-viewer) and [writeProtocolFrame] are the write entry
 * points; the stamped ones draw event_seq under the stream lock. All are
 * best-effort: a failed write returns false so the broadcaster can de-register a
 * dead viewer, and never throws into the caller
 * (a slow/dead observer must never block the initiator's turn — eaczz.6).
 */
internal class IrohViewerHandle(
    override val connectionId: String,
    private val sink: ViewerFrameSink,
    private val eventSeq: ConnectionEventSeq,
    private val streamWriteMutex: Mutex,
    private val frameParts: () -> Boolean,
    private val maxFrameBytes: Int,
    private val agentEventsGate: () -> Boolean = { false },
    private val conversationEventsGate: () -> Boolean = { false },
) : ViewerHandle {

    override fun receivesAgentEvents(): Boolean = agentEventsGate()

    override fun receivesConversationEvents(): Boolean = conversationEventsGate()

    /**
     * Re-wrap an already-cumulated + cm-stream-tagged assistant/tool/terminal
     * delta body with this viewer's own event_seq + idempotency_key and write it
     * to this viewer's stream. The [delta] is the SAME body the initiator
     * computed (do NOT re-accumulate or re-tag — that already happened once).
     */
    suspend fun writeBroadcastFrame(
        runtime: AppServerRuntimeScope,
        delta: JsonObject,
    ): Boolean = writeSerialized { streamDeltaFrame(runtime, delta, eventSeq.next()) }

    /**
     * Write a non-delta App Server frame (`update_loop_status`,
     * `turn_finished`, `update_queue`) with this viewer's own envelope: [type], [runtime],
     * event_seq, emitted_at and idempotency_key, followed by [fields].
     */
    suspend fun writeProtocolFrame(
        type: String,
        runtime: AppServerRuntimeScope,
        fields: JsonObject,
    ): Boolean = writeSerialized { protocolFrame(type, runtime, fields, eventSeq.next()).toString() }

    /**
     * Write an already-encoded wire frame verbatim (agent/conversation pushes,
     * subagent and approval replays). Best-effort; returns false on failure
     * without throwing.
     */
    override suspend fun writeFrame(frame: String): Boolean = writeSerialized { frame }

    /**
     * The ONE serialized write step for this connection's stream.
     * [encode] runs INSIDE [streamWriteMutex], so a stamped frame draws its event_seq in
     * the same critical section that puts it on the wire. Several chains write to one
     * handle (the connection's initiator queue, the node's observer queue, the
     * initiator-only busy rejection, redial replay); allocating before the lock let a
     * writer that lost the race put seq N+1 on the wire ahead of N, which the client's
     * EventSeqGapDetector reads as a Gap then a Stale. Under the lock, wire order is
     * seq order for every frame on the connection.
     */
    private suspend fun writeSerialized(encode: () -> String): Boolean = try {
        streamWriteMutex.withLock {
            val frame = encode()
            if (frameParts()) {
                IrohFrameCodec.encodeFrameParts(frame, maxFrameBytes).forEach { sink.writeAll(it) }
            } else {
                sink.writeAll(IrohFrameCodec.encodeFrame(frame, maxFrameBytes))
            }
        }
        true
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Telemetry.event(
            "IrohNode", "viewer.write.failed",
            "error" to (e.message ?: e.toString()),
            level = Telemetry.Level.WARN,
        )
        false
    }
}

/** The `stream_delta` envelope around an already-tagged [delta], stamped with [eventSeq]. */
internal fun streamDeltaFrame(runtime: AppServerRuntimeScope, delta: JsonObject, eventSeq: Long): String =
    buildJsonObject {
        put("type", "stream_delta")
        put("runtime", AppServerProtocol.json.encodeToJsonElement(AppServerRuntimeScope.serializer(), runtime))
        put("event_seq", eventSeq)
        put("emitted_at", Instant.now().toString())
        put("idempotency_key", "iroh-delta-${UUID.randomUUID()}")
        put("delta", delta)
    }.toString()

/**
 * Replays a redial's parked delta bodies ([parked], newline-separated) to the reconnecting
 * initiator's [viewer]. Each body is re-tagged and stamped at REPLAY time from the new
 * connection's counter, under the same lock as live writes. The
 * parked tail holds delta BODIES ([TurnFrameTracker] records them before any envelope), so
 * there is no original seq to keep, and one would be wrong anyway: it came from the dead
 * connection's counter, which the client's gap detector tracks as a separate generation.
 * Dedupe of replayed rows rides on their message ids and cm-stream tags, not on event_seq.
 * Throws when a write fails so the caller can re-park the tail for a later redial.
 */
internal suspend fun replayParkedDeltas(viewer: IrohViewerHandle, runtime: AppServerRuntimeScope, parked: String) {
    parked.split("\n").filter { it.isNotBlank() }.forEach { frameJson ->
        val delta = AppServerProtocol.json.parseToJsonElement(frameJson).jsonObject
        check(viewer.writeBroadcastFrame(runtime, tagStreamDeltaForOptimisticDedup(delta))) {
            "parked frame replay write failed"
        }
    }
}
