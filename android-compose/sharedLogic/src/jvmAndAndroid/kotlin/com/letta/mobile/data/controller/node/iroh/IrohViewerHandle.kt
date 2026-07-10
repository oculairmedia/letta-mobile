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
 * [writeFrame] (raw already-encoded wire frame, e.g. redial-parked replay) and
 * [writeBroadcastFrame] (a tagged delta body re-wrapped per-viewer) are the two
 * write entry points. Both are best-effort: a failed write returns false so the
 * broadcaster can de-register a dead viewer, and never throws into the caller
 * (a slow/dead observer must never block the initiator's turn — eaczz.6).
 */
internal class IrohViewerHandle(
    override val connectionId: String,
    private val sink: ViewerFrameSink,
    private val eventSeq: ConnectionEventSeq,
    private val streamWriteMutex: Mutex,
    private val frameParts: () -> Boolean,
    private val maxFrameBytes: Int,
) : ViewerHandle {

    /**
     * Re-wrap an already-cumulated + cm-stream-tagged assistant/tool/terminal
     * delta body with this viewer's own event_seq + idempotency_key and write it
     * to this viewer's stream. The [delta] is the SAME body the initiator
     * computed (do NOT re-accumulate or re-tag — that already happened once).
     */
    suspend fun writeBroadcastFrame(
        runtime: AppServerRuntimeScope,
        delta: JsonObject,
    ): Boolean {
        val frame = buildJsonObject {
            put("type", "stream_delta")
            put("runtime", AppServerProtocol.json.encodeToJsonElement(AppServerRuntimeScope.serializer(), runtime))
            put("event_seq", eventSeq.next())
            put("emitted_at", Instant.now().toString())
            put("idempotency_key", "iroh-delta-${UUID.randomUUID()}")
            put("delta", delta)
        }.toString()
        return writeFrame(frame)
    }

    /**
     * Write an already-encoded wire frame verbatim (used for redial replay of
     * parked frames, and by [writeBroadcastFrame]). Best-effort; returns false
     * on failure without throwing.
     */
    override suspend fun writeFrame(frame: String): Boolean = try {
        streamWriteMutex.withLock {
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
            "connectionId" to connectionId,
            "error" to (e.message ?: e.toString()),
            level = Telemetry.Level.WARN,
        )
        false
    }
}
