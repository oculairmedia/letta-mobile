package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * letta-mobile-bzvro.17 (F17): the conversations the user pinned. Pins are a client preference
 * (the reference app keeps them on the client too), stored in the settings store so they survive
 * a restart. A list shows pinned conversations first ([pinnedFirst]); a fork of a pinned
 * conversation is pinned too ([inherit]).
 *
 * Platform-neutral: desktop's sidebar and the shared nav drawer's row menus read the same store.
 */
class PinnedConversations(
    private val store: SecureSettingsStore,
    private val key: String = DEFAULT_KEY,
) {
    private val _pinned = MutableStateFlow(load())
    val pinned: StateFlow<Set<String>> = _pinned.asStateFlow()

    fun isPinned(conversationId: String): Boolean = conversationId in _pinned.value

    fun setPinned(conversationId: String, pinned: Boolean) {
        if (conversationId.isBlank()) return
        _pinned.update { current -> if (pinned) current + conversationId else current - conversationId }
        persist()
    }

    fun toggle(conversationId: String) = setPinned(conversationId, !isPinned(conversationId))

    /** A fork of a pinned conversation is pinned as well. */
    fun inherit(sourceId: String, forkId: String) {
        if (isPinned(sourceId)) setPinned(forkId, true)
    }

    private fun load(): Set<String> =
        store.getString(key)?.split(SEPARATOR)?.map(String::trim)?.filter(String::isNotEmpty)?.toSet().orEmpty()

    private fun persist() {
        val value = _pinned.value
        if (value.isEmpty()) store.remove(key) else store.putString(key, value.sorted().joinToString(SEPARATOR))
    }

    companion object {
        const val DEFAULT_KEY = "conversations.pinned"
        private const val SEPARATOR = "\n"

        /**
         * [items] with the pinned ones first. Each group keeps its order (the list's recency), so
         * pinning moves a row up without reordering the rest.
         */
        fun <T> pinnedFirst(items: List<T>, pinned: Set<String>, id: (T) -> String): List<T> {
            if (pinned.isEmpty()) return items
            val (top, rest) = items.partition { id(it) in pinned }
            return top + rest
        }
    }
}
