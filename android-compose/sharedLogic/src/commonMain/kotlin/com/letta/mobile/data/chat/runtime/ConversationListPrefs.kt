package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlin.jvm.JvmInline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** letta-mobile-zxk2n.5: the emoji a conversation wears before its title, one of [ConversationListPrefs.MARKER_CHOICES]. */
@JvmInline
value class ConversationMarker(val emoji: String)

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
 * Platform-neutral: hosts only hand it their [SecureSettingsStore]. The flows carry plain id
 * strings, as the list rows do.
 */
class ConversationListPrefs(
    private val store: SecureSettingsStore,
    private val keyPrefix: String = DEFAULT_PREFIX,
) {
    private val _settled = MutableStateFlow(lines(Key.Settled).toSet())
    val settled: StateFlow<Set<String>> = _settled.asStateFlow()

    private val _markers = MutableStateFlow(loadMarkers())
    val markers: StateFlow<Map<String, String>> = _markers.asStateFlow()

    private val _autoPinNewChats = MutableStateFlow(store.getString(key(Key.AutoPin))?.toBooleanStrictOrNull() ?: false)
    val autoPinNewChats: StateFlow<Boolean> = _autoPinNewChats.asStateFlow()

    fun setSettled(id: ConversationId, settled: Boolean) {
        if (id.value.isBlank()) return
        _settled.update { if (settled) it + id.value else it - id.value }
        persistLines(Key.Settled, _settled.value.sorted())
    }

    /** Sets the chat's marker; null (or an emoji outside [MARKER_CHOICES]) clears it. */
    fun setMarker(id: ConversationId, marker: ConversationMarker?) {
        if (id.value.isBlank()) return
        val chosen = marker?.emoji?.takeIf { it in MARKER_CHOICES }
        _markers.update { if (chosen == null) it - id.value else it + (id.value to chosen) }
        persistLines(Key.Markers, _markers.value.toSortedMap().map { (conversation, emoji) -> "$conversation$FIELD$emoji" })
    }

    fun setAutoPinNewChats(enabled: Boolean) {
        _autoPinNewChats.value = enabled
        store.putString(key(Key.AutoPin), enabled.toString())
    }

    /** A chat the user just started: pinned through [pin] when auto-pin is on. */
    fun onConversationCreated(id: ConversationId, pin: (ConversationId) -> Unit) {
        if (_autoPinNewChats.value && id.value.isNotBlank()) pin(id)
    }

    /** A chat that is gone for good (deleted) drops its marks. */
    fun forget(id: ConversationId) {
        if (id.value in _settled.value) setSettled(id, false)
        if (id.value in _markers.value) setMarker(id, null)
    }

    private fun loadMarkers(): Map<String, String> = lines(Key.Markers).mapNotNull { line ->
        val conversation = line.substringBefore(FIELD, missingDelimiterValue = "")
        val emoji = line.substringAfter(FIELD, missingDelimiterValue = "")
        (conversation to emoji).takeIf { conversation.isNotEmpty() && emoji in MARKER_CHOICES }
    }.toMap()

    private fun lines(name: Key): List<String> =
        store.getString(key(name))?.split(SEPARATOR)?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()

    private fun persistLines(name: Key, lines: List<String>) {
        if (lines.isEmpty()) store.remove(key(name)) else store.putString(key(name), lines.joinToString(SEPARATOR))
    }

    private fun key(name: Key) = "$keyPrefix.${name.suffix}"

    private enum class Key(val suffix: String) {
        Settled("settled"),
        Markers("markers"),
        AutoPin("autoPinNewChats"),
    }

    companion object {
        const val DEFAULT_PREFIX = "conversations.list"
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

/** The ids a list groups by: pinned chats lead, settled chats trail. */
data class ConversationListGroups(
    val pinned: Set<String> = emptySet(),
    val settled: Set<String> = emptySet(),
)

/** Where a conversation sits in the list: pinned chats first, settled chats last. */
object ConversationListOrder {
    /**
     * [items] with the pinned ones first and the settled ones last; each group keeps its order (the
     * list's recency). A pinned chat stays with the pins even when settled.
     */
    fun <T> arrange(items: List<T>, groups: ConversationListGroups, id: (T) -> String): List<T> {
        if (groups.pinned.isEmpty() && groups.settled.isEmpty()) return items
        val top = items.filter { id(it) in groups.pinned }
        val rest = items.filterNot { id(it) in groups.pinned }
        val (late, active) = rest.partition { id(it) in groups.settled }
        return top + active + late
    }
}

/** Which way "previous / next conversation" steps through the list. */
enum class ConversationStep { Previous, Next }

/** letta-mobile-zxk2n.5: previous / next conversation over the list as it is drawn. */
object ConversationNavigation {
    /**
     * The item one [step] from [current] in [ordered] (the list top to bottom), or null at either
     * end. With no current item (or one not listed), Next opens the first and Previous the last.
     */
    fun <T> adjacent(ordered: List<T>, current: T?, step: ConversationStep): T? {
        if (ordered.isEmpty()) return null
        val index = current?.let(ordered::indexOf) ?: -1
        if (index < 0) return if (step == ConversationStep.Next) ordered.first() else ordered.last()
        val target = if (step == ConversationStep.Next) index + 1 else index - 1
        return ordered.getOrNull(target)
    }
}
