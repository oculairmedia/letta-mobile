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
fun mergeStreamText(
    existing: String,
    incoming: String,
    canUseSnapshotMerge: Boolean,
    incomingIsForwardDelta: Boolean = true,
): StreamTextMergeResult {
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
        (overlapLen + 2 >= maxMatch)
    val branch = when {
        incoming.isEmpty() -> StreamTextMergeBranch.EMPTY_INCOMING
        canUseSnapshotMerge && incoming == existing -> StreamTextMergeBranch.EQUAL
        canUseSnapshotMerge && incoming.startsWith(existing) -> StreamTextMergeBranch.CUMULATIVE
        canUseSnapshotMerge && existing.startsWith(incoming) -> StreamTextMergeBranch.STALE
        canUseSnapshotMerge && existing.endsWith(incoming) -> StreamTextMergeBranch.SUFFIX_DUPLICATE
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
