package com.letta.mobile.data.timeline

/**
 * Branch taken while merging a streamed text frame into an existing timeline
 * event. Kept as production code so diagnostics and the live reducer cannot
 * drift apart.
 */
enum class StreamTextMergeBranch {
    EMPTY_INCOMING,
    EQUAL,
    CUMULATIVE,
    STALE,
    SUFFIX_DUPLICATE,
    // letta-mobile-k9y5d: two seq-id-carrying snapshots that share no clean
    // prefix/suffix relationship. Appending them would duplicate/garble the
    // text, so we keep the longer (more complete) snapshot instead.
    SNAPSHOT_CONFLICT,
    APPEND,
}

data class StreamTextMergeResult(
    val text: String,
    val branch: StreamTextMergeBranch,
    val garbleRisk: Boolean,
)

/**
 * Merge streamed text using the same rule for TimelineStreamReducer and CLI
 * diagnostics.
 *
 * Snapshot-style merges are only safe when both frames carry seq ids. Without
 * that ordering signal, unrelated deltas must append even if they happen to
 * resemble a prefix/suffix.
 *
 * letta-mobile-k9y5d: [incomingIsForwardDelta] tells us whether the incoming
 * frame is genuinely newer than the existing text (a higher seq id). A
 * forward delta that shares no prefix/suffix is an incremental continuation
 * and must APPEND (e.g. "Y" + "es ..." -> "Yes ..."). A NON-forward frame
 * (lower-or-equal seq id) that shares no clean prefix/suffix is a replayed /
 * re-delivered snapshot colliding with a stranded partial; appending it would
 * duplicate/garble the body, so we keep the longer (complete) text instead.
 * Defaults to true so existing callers keep the historical append behaviour.
 *
 * letta-mobile-mvcr4: a forward (higher-seq) snapshot whose body overlaps
 * the existing text WITHOUT a clean prefix/suffix relationship is also
 * effectively a non-clean snapshot — the same upstream re-tokenization or
 * repair can produce a near-match either at the start or mid/tail. With
 * the original APPEND rule, we duplicated the partial body and the
 * reveal/smoother downstream then visibly dropped the duplicated chars
 * (e.g. "complet " + "completed" -> "complet complet ed" -> rendered
 * truncated). Treat such near-overlap forward snapshots the same as
 * SNAPSHOT_CONFLICT: keep the longer complete text. This is strictly
 * safer than APPEND for seq-carrying snapshots and does not regress
 * forward delta appending because genuine forward deltas that share no
 * prefix/suffix (e.g. "Y" + "es ...") are exactly the case the test
 * suite guards; any near-match there is a coalesced snapshot, not an
 * increment.
 */
/**
 * letta-mobile-h30cy (the reducer-side token drop): when the stream delivers
 * INCREMENTAL single-token deltas (the live Iroh assistant path: each frame is a
 * new token like "I", "'m", " Lester" under one stable otid, NOT a cumulative
 * snapshot), a forward token that COINCIDENTALLY equals a prefix of the
 * accumulated text ("I" after "...bindings).\n\n", where the reply already
 * starts with "I'm") was misclassified as a STALE prefix-snapshot and DROPPED —
 * so the streamed row silently lost that character. Downstream, the reconciled
 * message.list final (the full text) then no longer matched the streamed row as
 * a prefix/superset, and mergeServerMessages appended it as a DUPLICATE row.
 *
 * [incrementalForwardAppend] tells the merge that this stream is incremental
 * (append-mode), so a genuine FORWARD delta must never be dropped as a STALE
 * prefix or a SUFFIX duplicate just because its bytes happen to coincide with the
 * start/end of the accumulated text — those coincidences are new tokens to
 * append, not re-delivered snapshots. Cumulative growth (incoming.startsWith
 * existing → CUMULATIVE) and non-forward re-deliveries are unaffected, so the
 * stable-id cumulative snapshot path (WS) must leave this false.
 *
 * letta-mobile-bn008 / letta-mobile-wucn: [isCumulativeStream] is the
 * CUMULATIVE-STREAM SHAPE signal that the upstream caller must derive from a
 * stream-shape property (NOT from per-frame seq-id availability). The Iroh
 * client boundary stamps a stable `cm-stream-<otid>` id and a stable
 * synthesized otid on every cumulative frame (the App Server's
 * `CumulativeStreamText` accumulator emits the cumulative text on every
 * wire-frame, and `tagStreamDeltaForOptimisticDedup` rewrites the id to
 * `cm-stream-<otid>`); an incremental HTTP-API SSE stream only stamps the
 * otid on the first frame and leaves subsequent frames otid=null. So
 *     confirmed.otid != null && confirmed.otid == existing.otid
 * is a reliable shape signal: true on a cumulative stream, false on an
 * incremental stream. The reducer derives this once per frame and passes it
 * here. EQUAL / CUMULATIVE are gated on (isCumulativeStream || canUseSnapshotMerge)
 * — true on EITHER an explicit ordering signal (seq ids on both sides) OR a
 * stream-shape signal (stable otid confirms the upstream is cumulative). The
 * seq-gated branches below (STALE, SUFFIX_DUPLICATE, SNAPSHOT_CONFLICT) remain
 * gated on canUseSnapshotMerge only, because those can DROP text and need the
 * full ordering signal.
 *
 * Why not gate on `incoming.startsWith(existing)` alone? Because the
 * letta-mobile-wucn counterexample demonstrates that an INCREMENTAL stream can
 * deliver a delta byte-identical to the accumulation so far (the 5th fragment
 * "The quick brown fox jumps over the lazy dog " equals the accumulator at
 * that point). On an incremental stream that delta is a genuine forward token
 * and MUST append; ungating EQUAL/CUMULATIVE on the byte shape alone (the
 * original bn008 fix) drops the 5th fragment. The shape signal must come from
 * the stream SHAPE — not the per-frame content. Defaults to false so existing
 * callers keep the historical append behaviour.
 */
fun mergeStreamText(
    existing: String,
    incoming: String,
    canUseSnapshotMerge: Boolean,
    incomingIsForwardDelta: Boolean = true,
    incrementalForwardAppend: Boolean = false,
    isCumulativeStream: Boolean = false,
): StreamTextMergeResult {
    // A forward delta in an incremental stream is always new text to append: a
    // prefix/suffix coincidence must NOT drop it (STALE/SUFFIX_DUPLICATE).
    val forwardIncrement = incrementalForwardAppend && incomingIsForwardDelta
    // letta-mobile-mvcr4: a forward (higher-seq) snapshot whose body
    // overlaps the existing text by NEARLY all of one side (only differs
    // by the leading or trailing few chars) is a re-tokenized snapshot,
    // not a forward delta. Without this branch we APPEND and duplicate
    // the partial body, and the downstream reveal then visibly
    // truncates the duplicate. We require a substantial overlap
    // (>= 4 chars in common AND overlap covers all but a few chars of
    // the shorter side) so genuine tiny forward deltas like "Y" + "es ..."
    // still APPEND.
    val shortLen = minOf(existing.length, incoming.length)
    val maxMatch = maxOf(existing.length, incoming.length)
    val overlapLen = if (shortLen >= 4) {
        // best suffix-of-incoming equal to prefix-of-existing length
        val k = longestCommonPrefixLength(existing, incoming)
        maxOf(k, longestCommonSuffixLength(existing, incoming))
    } else 0
    val nearOverlaps = canUseSnapshotMerge && overlapLen >= 4 &&
        (overlapLen.toDouble() / maxMatch.toDouble() >= 0.75)
    // letta-mobile-bn008 + letta-mobile-wucn: EQUAL and CUMULATIVE are
    // structurally unambiguous (a frame identical to the accumulated text, or
    // one that already CONTAINS it as a prefix, is never a legitimate forward
    // token on a cumulative stream — appending it stacks snapshots and produces
    // staircase garble ("Hey" + "HeyHey." -> "HeyHeyHey.")).
    //
    // gate must be derived from a *stream-shape* signal, not from per-frame
    // seq-id availability. Original bn008 fix ungated EQUAL/CUMULATIVE entirely
    // and broke the wucn counterexample (an incremental SSE stream whose 5th
    // fragment is byte-identical to the accumulator — that fragment IS a
    // forward token and must APPEND). The two gates are now OR'd:
    //   canUseSnapshotMerge  -> the existing seq-id ordering signal (drop-text
    //                           branches stay gated on this alone below)
    //   isCumulativeStream   -> the upstream-derived stable-otid stream-shape
    //                           signal (replaces the dropped-by-A-single-frame
    //                           seq-id check from the bn008 cascade)
    // STALE / SUFFIX_DUPLICATE / SNAPSHOT_CONFLICT remain gated on
    // canUseSnapshotMerge because they DROP text and need the full ordering
    // signal; isCumulativeStream alone is not sufficient for them.
    val cumulativeShapeAccepted = isCumulativeStream || canUseSnapshotMerge
    val branch = when {
        incoming.isEmpty() -> StreamTextMergeBranch.EMPTY_INCOMING
        incoming == existing && cumulativeShapeAccepted -> StreamTextMergeBranch.EQUAL
        existing.isNotEmpty() && incoming.startsWith(existing) && cumulativeShapeAccepted ->
            StreamTextMergeBranch.CUMULATIVE
        canUseSnapshotMerge && !forwardIncrement && existing.startsWith(incoming) -> StreamTextMergeBranch.STALE
        canUseSnapshotMerge && !forwardIncrement && existing.endsWith(incoming) -> StreamTextMergeBranch.SUFFIX_DUPLICATE
        // letta-mobile-mvcr4: near-overlap forward snapshot -> coalesce
        // to the longer complete text instead of duplicating.
        nearOverlaps -> StreamTextMergeBranch.SNAPSHOT_CONFLICT
        // letta-mobile-k9y5d: both frames carry a seq id but neither is a clean
        // prefix/suffix of the other. If the incoming is NOT a forward delta it
        // is a replayed/out-of-order snapshot, not a new continuation — appending
        // would duplicate the body and could drop a prefix, so keep the longer
        // (complete) snapshot. A forward delta still appends (incremental stream).
        canUseSnapshotMerge && !incomingIsForwardDelta -> StreamTextMergeBranch.SNAPSHOT_CONFLICT
        else -> StreamTextMergeBranch.APPEND
    }
    val text = when (branch) {
        StreamTextMergeBranch.EMPTY_INCOMING,
        StreamTextMergeBranch.EQUAL,
        StreamTextMergeBranch.STALE,
        StreamTextMergeBranch.SUFFIX_DUPLICATE -> existing
        StreamTextMergeBranch.CUMULATIVE -> incoming
        StreamTextMergeBranch.SNAPSHOT_CONFLICT -> if (incoming.length > existing.length) incoming else existing
        StreamTextMergeBranch.APPEND -> existing + incoming
    }
    return StreamTextMergeResult(
        text = text,
        branch = branch,
        garbleRisk = branch == StreamTextMergeBranch.APPEND &&
            existing.isNotEmpty() &&
            incoming.isNotEmpty() &&
            incoming.length < existing.length / 2,
    )
}

private fun longestCommonPrefixLength(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    var i = 0
    while (i < n && a[i] == b[i]) i++
    return i
}

private fun longestCommonSuffixLength(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    var i = 0
    while (i < n && a[a.length - 1 - i] == b[b.length - 1 - i]) i++
    return i
}
