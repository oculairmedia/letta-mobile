package com.letta.mobile.data.desktopshell

/** One thing the tab strip's picker can open in a tab: a conversation or a canvas. */
data class TabPickerItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val kind: Kind,
) {
    enum class Kind { CONVERSATION, CANVAS }
}

/**
 * Filters the picker's items by a typed query: every whitespace-separated word must appear in the
 * title or subtitle, case-insensitively, so "canvas mer" finds "Canvas (Meridian)". A blank query
 * keeps every item in its given order (recency, as the host supplies them).
 */
object TabPickerSearch {
    fun filter(items: List<TabPickerItem>, query: String): List<TabPickerItem> {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return items
        return items.filter { item ->
            val haystack = "${item.title} ${item.subtitle}".lowercase()
            words.all { word -> haystack.contains(word.lowercase()) }
        }
    }
}
