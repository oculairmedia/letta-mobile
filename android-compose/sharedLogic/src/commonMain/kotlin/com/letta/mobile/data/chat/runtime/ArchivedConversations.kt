package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.storage.SecureSettingsStore

/**
 * The conversations archived on this device: a client preference beside [PinnedConversations], kept
 * in the settings store so the conversation list's archive filter survives a restart.
 */
class ArchivedConversations(
    private val store: SecureSettingsStore,
    private val key: String = DEFAULT_KEY,
) {
    fun load(): Set<ConversationId> =
        store.getString(key)?.split(SEPARATOR)?.map(String::trim)?.filter(String::isNotEmpty)?.mapTo(mutableSetOf(), ::ConversationId)
            .orEmpty()

    fun save(ids: Set<ConversationId>) {
        if (ids.isEmpty()) store.remove(key) else store.putString(key, ids.map { it.value }.sorted().joinToString(SEPARATOR))
    }

    companion object {
        const val DEFAULT_KEY = "conversations.archived"
        private const val SEPARATOR = "\n"
    }
}
