package com.letta.mobile.data.transport.iroh

import com.letta.mobile.util.Telemetry

/**
 * letta-mobile-p0gc (causal slice C): aggregates the observer's
 * `ingest.skip_engine_owned` skips into ONE bounded telemetry event per turn
 * (plus a safety threshold flush) instead of one log line PER STREAM FRAME.
 *
 * Established evidence: while a local turn streams, every fanned-out frame for
 * that conversation is engine-owned, so the observer used to emit a
 * `skip_engine_owned` Telemetry.event per delta — hundreds of log lines per
 * turn on chatty runs, all carrying identical content.
 *
 * Aggregates carry:
 * - `conversationId` / `turnId` of the skipped stream,
 * - `skipped` — total frames skipped since the last flush,
 * - `deltaTypes` — per-delta-type counts (e.g. `tool_call_message=42,assistant=17`),
 *   highest count first, capped at [MAX_TYPE_LABELS] distinct types.
 *
 * Flush points (deterministic):
 * 1. threshold: after [flushEvery] recorded skips;
 * 2. turn switch: first record of a different (conversationId, turnId);
 * 3. end of turn: [endTurn] — called by the ingestor after it processed a
 *    terminal-candidate delta for the active local turn.
 *
 * Confined to the single-threaded observer collector (same confinement as
 * IrohChannelTransport's subagent reducer) — deliberately not synchronized.
 */
internal class EngineOwnedSkipTelemetry(
    private val flushEvery: Int = DEFAULT_FLUSH_EVERY,
    private val sink: (conversationId: String, turnId: String, skipped: Int, deltaTypes: String) -> Unit =
        { conversationId, turnId, skipped, deltaTypes ->
            Telemetry.event(
                "IrohObserver",
                "ingest.skip_engine_owned_aggregated",
                "conversationId" to conversationId,
                "turnId" to turnId,
                "skipped" to skipped.toString(),
                "deltaTypes" to deltaTypes,
            )
        },
) {
    private var currentConversationId: String? = null
    private var currentTurnId: String? = null
    private val typeCounts = LinkedHashMap<String, Int>()
    private var total = 0

    fun record(conversationId: String, turnId: String, deltaType: String) {
        if (currentTurnId != null && (conversationId != currentConversationId || turnId != currentTurnId)) {
            flush()
        }
        currentConversationId = conversationId
        currentTurnId = turnId
        typeCounts.merge(deltaType, 1, Int::plus)
        total++
        if (total >= flushEvery) flush()
    }

    /** Emit and clear the pending aggregate; keeps the current turn identity. */
    fun flush() {
        if (total == 0) return
        val label = typeCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(MAX_TYPE_LABELS)
            .joinToString(",") { "${it.key}=${it.value}" }
        sink(currentConversationId ?: "", currentTurnId ?: "", total, label)
        typeCounts.clear()
        total = 0
    }

    /** Turn ended (terminal observed or state reset): flush and drop identity. */
    fun endTurn() {
        flush()
        currentConversationId = null
        currentTurnId = null
    }

    companion object {
        const val DEFAULT_FLUSH_EVERY = 64
        private const val MAX_TYPE_LABELS = 8
    }
}
