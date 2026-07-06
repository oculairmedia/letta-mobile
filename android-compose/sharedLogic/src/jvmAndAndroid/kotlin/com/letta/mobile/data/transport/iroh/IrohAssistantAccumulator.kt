package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ServerFrame
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * h30cy — the ARCHITECTURAL fix: make the Iroh assistant/reasoning stream look
 * exactly like the rock-solid WebSocket stream, so the client reducer treats both
 * identically and Iroh inherits WS's drop-resilience.
 *
 * ## Why Iroh was fragile and WS was solid (two divergent choices)
 *
 * The transport itself is NOT the problem — Iroh streams over a reliable, ordered
 * QUIC BiStream (openBi), so no token is ever lost in transit (proven by the
 * app-server-iroh-probe: a live reply arrives byte-complete and in order). The
 * fragility came from two client-facing SHAPE choices that differ from WS:
 *
 *  1. IDENTITY. WS tags every streamed assistant frame with a STABLE
 *     `cm-stream-<otid>` id (shim spec §2.2/§4.2), so the reducer collapses the
 *     whole reply by simple id equality. The raw Iroh serve path emits a NEW
 *     rotating `letta-msg-*` id per fragment, forcing the reducer to *infer*
 *     identity from content/otid/run-id heuristics — the source of every
 *     merge/reconcile/dedup false-positive we chased.
 *
 *  2. TOKEN MODEL. WS frames are CUMULATIVE snapshots (each carries the full text
 *     so far), so a dropped frame self-heals — the next snapshot contains
 *     everything. Raw Iroh frames are INCREMENTAL single-token deltas (append),
 *     so a frame lost anywhere in the client dispatch layer is gone forever and
 *     the streamed row diverges from the reconciled final → duplicate row.
 *
 * ## The fix (this class)
 *
 * Accumulate the incremental Iroh deltas into CUMULATIVE content and stamp a
 * STABLE `cm-stream-<otid>` id, per (otid) group, on the serve side of the
 * client transport — BEFORE the frames enter the SharedFlow/reducer plumbing.
 * The reducer then sees WS-shaped frames: stable id + cumulative content. That
 * means:
 *
 *  - Identity is trivial (same id = same reply; no rotating-id inference).
 *  - Drops self-heal (the next cumulative frame carries all prior text), exactly
 *    like WS — a lost frame in the client dispatch layer no longer strands a
 *    fragment or produces a duplicate.
 *
 * This keeps the efficient incremental token *wire* (we do NOT ask the server to
 * resend cumulative text — the QUIC stream stays lean); the cumulative shape is
 * reconstructed locally from the reliably-ordered stream. It is the same reason
 * WS is solid, applied to Iroh, without a bandwidth-heavy cumulative wire or a
 * redundant gap-detect/replay protocol (QUIC already guarantees delivery).
 *
 * State is per-transport-instance and keyed by otid; a new turn's otid starts a
 * fresh accumulation. Bounded so a long-lived transport can't grow unbounded.
 */
internal class IrohAssistantAccumulator {
    private val lock = SynchronizedObject()
    // otid -> accumulated content so far. LinkedHashMap for bounded LRU eviction.
    private val assistantText = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>?): Boolean =
            size > MAX_TRACKED_OTIDS
    }
    private val reasoningText = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>?): Boolean =
            size > MAX_TRACKED_OTIDS
    }

    /**
     * Transform raw mapped serve frames into WS-shaped cumulative/stable-id
     * frames. Assistant and reasoning messages are accumulated; all other frames
     * pass through unchanged.
     */
    fun normalize(frames: List<ServerFrame>): List<ServerFrame> =
        frames.map { frame ->
            when (frame) {
                is ServerFrame.AssistantMessage -> normalizeAssistant(frame)
                is ServerFrame.ReasoningMessage -> normalizeReasoning(frame)
                else -> frame
            }
        }

    private fun normalizeAssistant(frame: ServerFrame.AssistantMessage): ServerFrame.AssistantMessage {
        val otid = frame.otid ?: frame.id
        val cumulative = synchronized(lock) {
            // The mapper anchors a stable per-turn otid, so all fragments of one
            // reply share it. Accumulate the incremental delta into the running
            // text. If a later frame is somehow already cumulative (contains the
            // prior text as a prefix), prefer the longer — never double-append.
            val prior = assistantText[otid].orEmpty()
            val incoming = frame.content
            val next = when {
                incoming.isEmpty() -> prior
                incoming.startsWith(prior) && incoming.length >= prior.length -> incoming
                prior.startsWith(incoming) -> prior
                else -> prior + incoming
            }
            assistantText[otid] = next
            next
        }
        return frame.copy(
            id = stableId(otid),
            otid = otid,
            content = cumulative,
        )
    }

    private fun normalizeReasoning(frame: ServerFrame.ReasoningMessage): ServerFrame.ReasoningMessage {
        // Reasoning frames carry no otid; group by the stable per-message id the
        // mapper assigns (iroh-reasoning-<runId>-<turnId>).
        val key = frame.id
        val cumulative = synchronized(lock) {
            val prior = reasoningText[key].orEmpty()
            val incoming = frame.reasoning
            val next = when {
                incoming.isEmpty() -> prior
                incoming.startsWith(prior) && incoming.length >= prior.length -> incoming
                prior.startsWith(incoming) -> prior
                else -> prior + incoming
            }
            reasoningText[key] = next
            next
        }
        return frame.copy(
            id = stableId(key),
            reasoning = cumulative,
        )
    }

    private fun stableId(otid: String): String =
        if (otid.startsWith(CM_STREAM_PREFIX)) otid else "$CM_STREAM_PREFIX$otid"

    private companion object {
        const val CM_STREAM_PREFIX = "cm-stream-"
        const val MAX_TRACKED_OTIDS = 64
    }
}
