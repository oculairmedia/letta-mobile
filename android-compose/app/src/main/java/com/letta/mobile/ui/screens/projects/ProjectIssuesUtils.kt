package com.letta.mobile.ui.screens.projects

// ⚡ Bolt Optimization: Replace asSequence map chains with an indexed loop
// to prevent iterator allocations on every scroll frame, stopping GC jank.
// Extracted to a separate file to keep ProjectIssuesScreen file complexity low.
@androidx.annotation.VisibleForTesting
internal fun findFirstVisibleIssue(
    visibleItems: List<androidx.compose.foundation.lazy.LazyListItemInfo>,
    issueIds: Set<String>
): String? {
    for (i in visibleItems.indices) {
        val rawKey = visibleItems[i].key as? String ?: continue
        val key = if (rawKey.startsWith("ready-")) rawKey.removePrefix("ready-") else rawKey
        if (issueIds.contains(key)) {
            return key
        }
    }
    return null
}
