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
 */
fun mergeStreamText(
    existing: String,
    incoming: String,
    canUseSnapshotMerge: Boolean,
    incomingIsForwardDelta: Boolean = true,
): StreamTextMergeResult {
    val branch = when {
        incoming.isEmpty() -> StreamTextMergeBranch.EMPTY_INCOMING
        canUseSnapshotMerge && incoming == existing -> StreamTextMergeBranch.EQUAL
        canUseSnapshotMerge && incoming.startsWith(existing) -> StreamTextMergeBranch.CUMULATIVE
        canUseSnapshotMerge && existing.startsWith(incoming) -> StreamTextMergeBranch.STALE
        canUseSnapshotMerge && existing.endsWith(incoming) -> StreamTextMergeBranch.SUFFIX_DUPLICATE
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
