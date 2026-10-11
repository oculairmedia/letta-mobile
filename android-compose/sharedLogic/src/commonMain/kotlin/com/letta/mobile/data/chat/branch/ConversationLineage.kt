package com.letta.mobile.data.chat.branch

import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The conversation a fork was made from, and how. */
data class ConversationParent(val conversationId: String, val kind: BranchKind = BranchKind.Fork)

/**
 * letta-mobile-7mys7 (G37): which conversation each fork came from, so the drawer can show
 * "Forked from …" under it. letta-code's `conversation_fork` keeps no link to the source (its
 * answer is the new id only), so the link is a client record, kept in the settings store like
 * [com.letta.mobile.data.chat.runtime.PinnedConversations] so it survives a restart.
 */
class ConversationLineage(
    private val store: SecureSettingsStore,
    private val key: String = DEFAULT_KEY,
) {
    private val _parents = MutableStateFlow(load())
    val parents: StateFlow<Map<String, ConversationParent>> = _parents.asStateFlow()

    fun parentOf(conversationId: String): ConversationParent? = _parents.value[conversationId]

    fun record(forkId: String, parent: ConversationParent) {
        if (forkId.isBlank() || parent.conversationId.isBlank() || forkId == parent.conversationId) return
        _parents.update { current -> (current - forkId + (forkId to parent)).newest(MAX_ENTRIES) }
        persist()
    }

    fun forget(conversationId: String) {
        if (conversationId !in _parents.value) return
        _parents.update { it - conversationId }
        persist()
    }

    private fun load(): Map<String, ConversationParent> =
        store.getString(key).orEmpty().lineSequence().mapNotNull(::decode).toMap(LinkedHashMap())

    private fun persist() {
        val value = _parents.value
        if (value.isEmpty()) {
            store.remove(key)
        } else {
            store.putString(key, value.entries.joinToString(LINE) { (fork, parent) -> listOf(fork, parent.conversationId, parent.kind.name).joinToString(FIELD) })
        }
    }

    companion object {
        const val DEFAULT_KEY = "conversations.lineage"
        private const val LINE = "\n"
        private const val FIELD = "\t"
        private const val MAX_ENTRIES = 500

        private fun decode(line: String): Pair<String, ConversationParent>? {
            val fields = line.split(FIELD)
            val fork = fields.getOrNull(0)?.trim().orEmpty()
            val parent = fields.getOrNull(1)?.trim().orEmpty()
            if (fork.isEmpty() || parent.isEmpty()) return null
            val kind = BranchKind.entries.firstOrNull { it.name == fields.getOrNull(2)?.trim() } ?: BranchKind.Fork
            return fork to ConversationParent(parent, kind)
        }

        /** The [limit] most recently recorded entries (insertion order is recency). */
        private fun <K, V> Map<K, V>.newest(limit: Int): Map<K, V> =
            if (size <= limit) this else entries.drop(size - limit).associateTo(LinkedHashMap()) { it.toPair() }
    }
}
