package com.letta.mobile.data.voice.speech

/**
 * How a [SentenceChunker] sizes its segments, in characters; every limit is checked here, once.
 *
 * - [maxSegmentLength]: no segment is longer; longer text is cut at the last clause break or space.
 * - [firstClauseMinLength]: when set, the first segment is released early, at the first clause
 *   break once that clause is at least this long.
 * - [minSegmentLength]: when set, the sentences after the first are merged until a segment is at
 *   least this long (never more than [maxSegmentLength]); null keeps one sentence per segment.
 * - [tailAbsorbLength]: when set, a sentence shorter than this joins the merged segment before it
 *   instead of going out alone. It only applies while merging is on: with a null [minSegmentLength]
 *   there is nothing to join to, and the setting has no effect.
 */
data class SegmentSizing(
    val maxSegmentLength: Int = SentenceChunker.DEFAULT_MAX_SEGMENT_LENGTH,
    val firstClauseMinLength: Int? = null,
    val minSegmentLength: Int? = null,
    val tailAbsorbLength: Int? = null,
) {
    init {
        require(maxSegmentLength >= MIN_SEGMENT_LENGTH) { "maxSegmentLength too small" }
        require(firstClauseMinLength == null || firstClauseMinLength > 0) { "firstClauseMinLength must be positive" }
        require(minSegmentLength == null || minSegmentLength > 0) { "minSegmentLength must be positive" }
        require(tailAbsorbLength == null || tailAbsorbLength > 0) { "tailAbsorbLength must be positive" }
    }

    /** The merge goal: [minSegmentLength] capped by [maxSegmentLength]; null when sentences are not merged. */
    internal val mergeGoal: Int? get() = minSegmentLength?.coerceAtMost(maxSegmentLength)

    /** Limits. */
    companion object {
        /** The smallest [maxSegmentLength] a chunker accepts. */
        const val MIN_SEGMENT_LENGTH: Int = 16
    }
}
