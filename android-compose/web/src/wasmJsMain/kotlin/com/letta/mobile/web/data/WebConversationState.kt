package com.letta.mobile.web.data

internal fun upsertWebChatEntry(
    entries: List<WebChatEntry>,
    entry: WebChatEntry,
): List<WebChatEntry> {
    val index = entries.indexOfFirst { it.id == entry.id }
    return if (index < 0) entries + entry else entries.toMutableList().apply { this[index] = entry }
}

internal fun replaceOptimisticTurn(
    entries: List<WebChatEntry>,
    userId: String,
    assistantId: String,
    updates: List<WebChatEntry>,
): List<WebChatEntry> = updates.fold(
    entries.filterNot { it.id == userId || it.id == assistantId },
    ::upsertWebChatEntry,
)
