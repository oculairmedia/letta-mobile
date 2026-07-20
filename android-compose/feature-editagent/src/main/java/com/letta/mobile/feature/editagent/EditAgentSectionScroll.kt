package com.letta.mobile.feature.editagent

import androidx.compose.foundation.lazy.LazyListState

private const val PROGRESSIVE_SCROLL_MAX_STEPS = 16

internal suspend fun LazyListState.animateScrollToKey(
    targetKey: Any,
) {
    indexOfVisibleKey(targetKey)?.let { index ->
        animateScrollToItem(index)
        return
    }
    scrollProgressivelyToKey(targetKey)
}

private fun LazyListState.indexOfVisibleKey(targetKey: Any): Int? =
    layoutInfo.visibleItemsInfo.firstOrNull { it.key == targetKey }?.index

private suspend fun LazyListState.scrollProgressivelyToKey(
    targetKey: Any,
) {
    val total = layoutInfo.totalItemsCount
    if (total == 0) return
    var lastSeenIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
    var safety = 0
    while (lastSeenIndex < total - 1 && safety < PROGRESSIVE_SCROLL_MAX_STEPS) {
        val nextStart = (lastSeenIndex + 1).coerceAtMost(total - 1)
        scrollToItem(nextStart)
        indexOfVisibleKey(targetKey)?.let { found ->
            animateScrollToItem(found)
            return
        }
        val newLast = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return
        if (newLast <= lastSeenIndex) return
        lastSeenIndex = newLast
        safety++
    }
}
