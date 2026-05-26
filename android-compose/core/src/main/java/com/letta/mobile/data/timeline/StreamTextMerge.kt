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
        else -> StreamTextMergeBranch.APPEND
    }
    val text = when (branch) {
        StreamTextMergeBranch.EMPTY_INCOMING,
        StreamTextMergeBranch.EQUAL,
        StreamTextMergeBranch.STALE,
        StreamTextMergeBranch.SUFFIX_DUPLICATE -> existing
        StreamTextMergeBranch.CUMULATIVE -> incoming
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
