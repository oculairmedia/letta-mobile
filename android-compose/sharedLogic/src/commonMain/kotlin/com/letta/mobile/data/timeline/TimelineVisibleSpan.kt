package com.letta.mobile.data.timeline

/** Visible rows in the paged list's index space (0 is the newest edge). */
data class TimelineVisibleSpan(
    val firstIndex: Int,
    val count: Int,
) {
    /**
     * Visible rows plus one page on each side, clamped to the resident snapshot.
     * An empty or unknown span keeps a single page at the newest edge.
     */
    fun keepRange(pageSize: Int, residentCount: Int): IntRange {
        if (pageSize <= 0 || residentCount <= 0) return IntRange.EMPTY
        val visibleCount = count.coerceAtLeast(1)
        val first = firstIndex.coerceIn(0, residentCount - 1)
        val start = (first - pageSize).coerceAtLeast(0)
        val endExclusive = (first + visibleCount + pageSize).coerceAtMost(residentCount)
        if (start >= endExclusive) return IntRange.EMPTY
        return start until endExclusive
    }
}
