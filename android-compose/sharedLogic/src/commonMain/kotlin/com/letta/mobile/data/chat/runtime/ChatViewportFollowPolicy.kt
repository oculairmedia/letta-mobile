package com.letta.mobile.data.chat.runtime

/**
 * Platform-neutral policy for chat viewports that should follow the latest
 * message without yanking users who are reading history.
 */
data class ChatViewportSnapshot(
    val totalItems: Int,
    val lastVisibleIndex: Int?,
    val isUserScrolling: Boolean = false,
    val dateHeaderOffset: Int = 0,
)

object ChatViewportFollowPolicy {
    fun latestIndex(itemCount: Int): Int =
        (itemCount - 1).coerceAtLeast(0)

    fun isNearLatest(
        snapshot: ChatViewportSnapshot,
        thresholdItems: Int = DEFAULT_NEAR_LATEST_THRESHOLD_ITEMS,
        dateHeaderOffset: Int = snapshot.dateHeaderOffset,
    ): Boolean {
        if (snapshot.totalItems <= 0) return true
        val lastVisibleIndex = snapshot.lastVisibleIndex ?: return true
        val effectiveThreshold = thresholdItems + dateHeaderOffset.coerceAtLeast(0)
        return lastVisibleIndex >= snapshot.totalItems - 1 - effectiveThreshold
    }

    fun shouldShowScrollToLatest(
        snapshot: ChatViewportSnapshot,
        thresholdItems: Int = DEFAULT_NEAR_LATEST_THRESHOLD_ITEMS,
        dateHeaderOffset: Int = snapshot.dateHeaderOffset,
    ): Boolean =
        snapshot.totalItems > 0 && !isNearLatest(snapshot, thresholdItems, dateHeaderOffset)

    fun shouldUpdateFollowModeAfterScroll(snapshot: ChatViewportSnapshot): Boolean =
        !snapshot.isUserScrolling

    fun nextFollowModeAfterScroll(
        currentFollowMode: Boolean,
        snapshot: ChatViewportSnapshot,
        thresholdItems: Int = DEFAULT_NEAR_LATEST_THRESHOLD_ITEMS,
        dateHeaderOffset: Int = snapshot.dateHeaderOffset,
    ): Boolean =
        if (shouldUpdateFollowModeAfterScroll(snapshot)) {
            isNearLatest(snapshot, thresholdItems, dateHeaderOffset)
        } else {
            currentFollowMode
        }

    fun shouldAutoFollow(
        followMode: Boolean,
        itemCount: Int,
    ): Boolean =
        followMode && itemCount > 0

    const val DEFAULT_NEAR_LATEST_THRESHOLD_ITEMS: Int = 1
}
