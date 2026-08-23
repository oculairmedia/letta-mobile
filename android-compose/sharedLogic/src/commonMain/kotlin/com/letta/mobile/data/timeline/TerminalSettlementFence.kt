package com.letta.mobile.data.timeline

import com.letta.mobile.util.Telemetry

/**
 * letta-mobile-9lgfu (P0 data-integrity): terminal settlement fence.
 *
 * Acceptance criterion 2 of the bead: "Terminal events cannot finalize or
 * persist an accumulator whose sequence is older than an already-received
 * content delta."
 *
 * A SETTLEMENT write is any fold that REPLACES accumulated assistant text
 * wholesale (the reducer's synthetic-live -> real-final snapshot promotion,
 * and the reconcile collapse paths in [Timeline.mergeServerMessages]) rather
 * than growing it through [mergeStreamText]. Before this fence those writes
 * were unconditional, so a terminal/reconcile snapshot whose frame sequence
 * predates the last content delta already folded into the row could REGRESS
 * the body — the reported "assistant stream drops its final words" symptom:
 * the bubble ends mid-sentence yet carries the settled terminal timestamp.
 *
 * Fence rule (sequence-first, content-guarded):
 *  - Both sequences known AND terminalSeq < lastDeltaSeq (the terminal is
 *    provably older than a received delta) AND the terminal text is NOT a
 *    superset of what we already hold -> BLOCKED. The caller must keep the
 *    accumulated text (fold conservatively via
 *    `mergeStreamText(..., incomingIsForwardDelta = false)`, which only ever
 *    keeps-or-grows relative to the accumulator) or skip the replace.
 *  - Stale seq BUT the terminal text is a strict/equal superset of the
 *    accumulated text -> allowed: settling to it loses nothing we received.
 *    This is the first-word-lag / re-tokenized-shape case the synthetic
 *    promotion path exists for.
 *  - Either sequence absent -> not fenced (seq-less streams keep their
 *    historical behavior; every seq-less fold already routes through
 *    [mergeStreamText], which never regresses below the accumulator).
 *
 * Telemetry: every STALE-seq decision emits "terminal.settlement.fence"
 * (tag "TimelineSync") discriminating lastDeltaSeq vs terminalSeq so field
 * reports can diagnose tail loss without a device debugger.
 */
internal data class TerminalSettlementFenceDecision(
    /** True when the caller must NOT settle the accumulator to the incoming text. */
    val blocked: Boolean,
    /** Machine-readable discriminator for telemetry/tests. */
    val reason: String,
)

internal fun evaluateTerminalSettlementFence(
    conversationId: String,
    serverId: String,
    lastDeltaSeqId: Int?,
    terminalSeqId: Int?,
    accumulatedText: String,
    terminalText: String,
): TerminalSettlementFenceDecision {
    val lastSeq = lastDeltaSeqId
    val terminalSeq = terminalSeqId
    val bothSeqsKnown = lastSeq != null && terminalSeq != null
    val terminalIsStale = bothSeqsKnown && terminalSeq < lastSeq

    if (!bothSeqsKnown) {
        return TerminalSettlementFenceDecision(blocked = false, reason = "seq_absent")
    }
    if (!terminalIsStale) {
        return TerminalSettlementFenceDecision(blocked = false, reason = "seq_forward_or_equal")
    }

    // The terminal frame is OLDER than a delta we already folded in. It may
    // still be safe to settle with when it carries everything we hold plus
    // more (server coalesced/reordered delivery) — content decides.
    val supersetOfAccumulated = isContentSuperset(terminalText, accumulatedText)
    if (supersetOfAccumulated) {
        Telemetry.event(
            "TimelineSync", "terminal.settlement.fence",
            "conversationId" to conversationId,
            "serverId" to serverId,
            "decision" to "allowed_superset",
            "fenceReason" to "stale_seq_but_superset",
            "lastDeltaSeq" to lastSeq,
            "terminalSeq" to terminalSeq,
            "existingLen" to accumulatedText.length,
            "incomingLen" to terminalText.length,
        )
        return TerminalSettlementFenceDecision(blocked = false, reason = "stale_seq_but_superset")
    }

    Telemetry.event(
        "TimelineSync", "terminal.settlement.fence",
        "conversationId" to conversationId,
        "serverId" to serverId,
        "decision" to "blocked",
        "fenceReason" to "stale_terminal_not_superset",
        "lastDeltaSeq" to lastSeq,
        "terminalSeq" to terminalSeq,
        "existingLen" to accumulatedText.length,
        "incomingLen" to terminalText.length,
        level = Telemetry.Level.WARN,
    )
    return TerminalSettlementFenceDecision(blocked = true, reason = "stale_terminal_not_superset")
}

/**
 * True when [candidate] carries at least all of [accumulated] (trimmed
 * equality or containment). Blank accumulators are trivially superseded.
 * Deliberately byte-strict (no letters/digits normalization): anything the
 * strict check rejects falls into the conservative fold path, which still
 * never loses accumulated text — so a mangled-vs-clean mismatch degrades to
 * keep-longer (the letta-mobile-k9y5d tradeoff), never to truncation.
 */
private fun isContentSuperset(candidate: String, accumulated: String): Boolean {
    val candidateTrimmed = candidate.trim()
    val accumulatedTrimmed = accumulated.trim()
    if (accumulatedTrimmed.isEmpty()) return true
    if (candidateTrimmed.isEmpty()) return false
    return candidateTrimmed == accumulatedTrimmed || candidateTrimmed.contains(accumulatedTrimmed)
}
