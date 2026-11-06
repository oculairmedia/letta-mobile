package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.objectverbs.ObjectRef
import com.letta.mobile.data.objectverbs.VerbOutcome
import com.letta.mobile.data.objectverbs.binding.DevicePins
import com.letta.mobile.data.pins.LegacyPinSources
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.flow.DerivedStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 *  (F17): the conversations the user pinned. Pins are a client preference
 * (the reference app keeps them on the client too), stored in the settings store so they survive
 * a restart. A list shows pinned conversations first ([pinnedFirst]); a fork of a pinned
 * conversation is pinned too ([inherit]).
 *
 * Platform-neutral: desktop's sidebar and the shared nav drawer's row menus read the same store.
 */
class PinnedConversations(
    private val store: SecureSettingsStore,
    private val key: String = DEFAULT_KEY,
    source: StateFlow<String?> = MutableStateFlow(null),
) {
    val binding = DevicePins.bind(store, source)
    init {
        val available = if (key == DEFAULT_KEY) setOf(DEFAULT_KEY) else setOf(DEFAULT_KEY, key)
        binding.store.importLegacy(LegacyPinSources.secure(store, available))
    }
    val pinned: StateFlow<Set<String>> = DerivedStateFlow(
        binding.ids("conversation").map { it.toSet() }, { binding.ids("conversation").value.toSet() },
    )

    fun isPinned(conversationId: String): Boolean = conversationId in pinned.value

    fun setPinned(conversationId: String, pinned: Boolean) {
        if (conversationId.isBlank()) return
        val result = binding.setPinned(ObjectRef("conversation", conversationId), pinned)
        check(result is VerbOutcome.Updated) { "Pin update failed" }
    }

    fun toggle(conversationId: String) = setPinned(conversationId, !isPinned(conversationId))

    /** Fork inheritance uses the same provider as a row's explicit pin. */
    fun inherit(sourceId: String, forkId: String) {
        if (isPinned(sourceId)) setPinned(forkId, true)
    }

    companion object {
        const val DEFAULT_KEY = "conversations.pinned"

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
