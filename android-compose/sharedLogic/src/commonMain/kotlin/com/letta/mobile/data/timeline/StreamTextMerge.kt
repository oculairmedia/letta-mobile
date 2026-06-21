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
 */
fun mergeStreamText(
    existing: String,
    incoming: String,
    canUseSnapshotMerge: Boolean,
): StreamTextMergeResult {
    val branch = when {
        incoming.isEmpty() -> StreamTextMergeBranch.EMPTY_INCOMING
        canUseSnapshotMerge && incoming == existing -> StreamTextMergeBranch.EQUAL
        canUseSnapshotMerge && incoming.startsWith(existing) -> StreamTextMergeBranch.CUMULATIVE
        canUseSnapshotMerge && existing.startsWith(incoming) -> StreamTextMergeBranch.STALE
        canUseSnapshotMerge && existing.endsWith(incoming) -> StreamTextMergeBranch.SUFFIX_DUPLICATE
        // letta-mobile-k9y5d: both frames carry a seq id (so they are
        // cumulative snapshots of the same logical message), but neither is a
        // clean prefix/suffix of the other. This happens on replay /
        // out-of-order resume, where a complete snapshot can collide with a
        // stranded partial. Appending would duplicate the body and never drops
        // a prefix; keeping the longer snapshot preserves the complete text.
        canUseSnapshotMerge -> StreamTextMergeBranch.SNAPSHOT_CONFLICT
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
