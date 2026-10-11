package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * letta-mobile-zxk2n.5 (G37): the conversation list's client-side marks, kept in the settings store
 * so they survive a restart. letta-code's conversation record has no field for any of them
 * (`conversation_update` takes archived, summary, model and the like), so, like pins, they live on
 * the client:
 *
 * - **settled**: a chat the user has finished with for now. It stays out of Archived but sinks
 *   below the active chats; "Move to active" lifts it back.
 * - **marker**: one emoji drawn before the chat's title, picked from [MARKER_CHOICES].
 * - **auto-pin new chats**: a chat the user starts is pinned at once.
 *
 * Platform-neutral: hosts only hand it their [SecureSettingsStore].
 */
class ConversationListPrefs(
    private val store: SecureSettingsStore,
    private val keyPrefix: String = DEFAULT_PREFIX,
) {
    private val _settled = MutableStateFlow(loadSet(SETTLED))
    val settled: StateFlow<Set<String>> = _settled.asStateFlow()

    private val _markers = MutableStateFlow(loadMarkers())
    val markers: StateFlow<Map<String, String>> = _markers.asStateFlow()

    private val _autoPinNewChats = MutableStateFlow(store.getString(key(AUTO_PIN))?.toBooleanStrictOrNull() ?: false)
    val autoPinNewChats: StateFlow<Boolean> = _autoPinNewChats.asStateFlow()

    fun setSettled(conversationId: String, settled: Boolean) {
        if (conversationId.isBlank()) return
        _settled.update { if (settled) it + conversationId else it - conversationId }
        persistLines(SETTLED, _settled.value.sorted())
    }

    /** Sets the chat's marker; null (or an emoji outside [MARKER_CHOICES]) clears it. */
    fun setMarker(conversationId: String, marker: String?) {
        if (conversationId.isBlank()) return
        val chosen = marker?.takeIf { it in MARKER_CHOICES }
        _markers.update { if (chosen == null) it - conversationId else it + (conversationId to chosen) }
        persistLines(MARKERS, _markers.value.toSortedMap().map { (id, emoji) -> "$id$FIELD$emoji" })
    }

    fun setAutoPinNewChats(enabled: Boolean) {
        _autoPinNewChats.value = enabled
        store.putString(key(AUTO_PIN), enabled.toString())
    }

    /** A chat the user just started: pinned through [pin] when auto-pin is on. */
    fun onConversationCreated(conversationId: String, pin: (String) -> Unit) {
        if (_autoPinNewChats.value && conversationId.isNotBlank()) pin(conversationId)
    }

    /** A chat that is gone for good (deleted) drops its marks. */
    fun forget(conversationId: String) {
        if (conversationId in _settled.value) setSettled(conversationId, false)
        if (conversationId in _markers.value) setMarker(conversationId, null)
    }

    private fun loadSet(name: String): Set<String> = lines(name).toSet()

    private fun loadMarkers(): Map<String, String> = lines(MARKERS).mapNotNull { line ->
        val id = line.substringBefore(FIELD, missingDelimiterValue = "")
        val emoji = line.substringAfter(FIELD, missingDelimiterValue = "")
        (id to emoji).takeIf { id.isNotEmpty() && emoji in MARKER_CHOICES }
    }.toMap()

    private fun lines(name: String): List<String> =
        store.getString(key(name))?.split(SEPARATOR)?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()

    private fun persistLines(name: String, lines: List<String>) {
        if (lines.isEmpty()) store.remove(key(name)) else store.putString(key(name), lines.joinToString(SEPARATOR))
    }

    private fun key(name: String) = "$keyPrefix.$name"

    companion object {
        const val DEFAULT_PREFIX = "conversations.list"
        private const val SETTLED = "settled"
        private const val MARKERS = "markers"
        private const val AUTO_PIN = "autoPinNewChats"
        private const val SEPARATOR = "\n"
        private const val FIELD = "\t"

        /** The markers a chat can wear: a small fixed set reads at a glance in a narrow list. */
        val MARKER_CHOICES: List<String> = listOf(
            "⭐", // star
            "🔥", // fire
            "✅", // check mark
            "🐛", // bug
            "💡", // light bulb
            "🚧", // construction
            "❓", // question mark
            "📝", // memo
        )
    }
}

/** Where a conversation sits in the list: pinned chats first, settled chats last. */
object ConversationListOrder {
    /**
     * [items] with the pinned ones first and the settled ones last; each group keeps its order (the
     * list's recency). A pinned chat stays with the pins even when settled.
     */
    fun <T> arrange(items: List<T>, pinned: Set<String>, settled: Set<String>, id: (T) -> String): List<T> {
        if (pinned.isEmpty() && settled.isEmpty()) return items
        val top = items.filter { id(it) in pinned }
        val rest = items.filterNot { id(it) in pinned }
        val (late, active) = rest.partition { id(it) in settled }
        return top + active + late
    }
}

/** Which way "previous / next conversation" steps through the list. */
enum class ConversationStep { Previous, Next }

/** letta-mobile-zxk2n.5: previous / next conversation over the list as it is drawn. */
object ConversationNavigation {
    /**
     * The conversation one [step] from [currentId] in [orderedIds] (the list top to bottom), or null
     * at either end. With no current conversation (or one not listed), Next opens the first and
     * Previous the last.
     */
    fun adjacent(orderedIds: List<String>, currentId: String?, step: ConversationStep): String? {
        if (orderedIds.isEmpty()) return null
        val index = currentId?.let(orderedIds::indexOf) ?: -1
        if (index < 0) return if (step == ConversationStep.Next) orderedIds.first() else orderedIds.last()
        val target = if (step == ConversationStep.Next) index + 1 else index - 1
        return orderedIds.getOrNull(target)
    }
}
