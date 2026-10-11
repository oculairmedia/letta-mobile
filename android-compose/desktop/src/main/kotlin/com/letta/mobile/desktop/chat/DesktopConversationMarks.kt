package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.display.ChatDisplayOptions
import com.letta.mobile.data.chat.runtime.ConversationListPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * letta-mobile-zxk2n.5 (G37, G91): the desktop's binding of the shared client marks: settled chats,
 * markers, auto-pin of new chats, and the chat's display options. The logic is shared
 * ([ConversationListPrefs], [com.letta.mobile.data.chat.display.ChatDisplayPrefs]); this only reads
 * the desktop's settings store through [DesktopConversationPrefs]. Null prefs keep everything off.
 */
internal class DesktopConversationMarks(private val prefs: DesktopConversationPrefs) {
    private val marks = prefs.listMarks

    val settled: StateFlow<Set<String>> = marks?.settled ?: MutableStateFlow(emptySet<String>()).asStateFlow()
    val markers: StateFlow<Map<String, String>> = marks?.markers ?: MutableStateFlow(emptyMap<String, String>()).asStateFlow()
    val autoPinNewChats: StateFlow<Boolean> = marks?.autoPinNewChats ?: MutableStateFlow(false).asStateFlow()
    val displayOptions: StateFlow<ChatDisplayOptions> =
        prefs.display?.options ?: MutableStateFlow(ChatDisplayOptions.Default).asStateFlow()

    /** Whether this desktop keeps the marks at all; without them the row menu offers neither. */
    val enabled: Boolean get() = marks != null

    val markerChoices: List<String> get() = ConversationListPrefs.MARKER_CHOICES

    fun setSettled(conversationId: String, settled: Boolean) {
        marks?.setSettled(conversationId, settled)
    }

    fun setMarker(conversationId: String, marker: String?) {
        marks?.setMarker(conversationId, marker)
    }

    fun setAutoPinNewChats(enabled: Boolean) {
        marks?.setAutoPinNewChats(enabled)
    }

    fun setDisplayOptions(options: ChatDisplayOptions) {
        prefs.display?.update { options }
    }

    /** A chat the user just started: pinned when auto-pin is on. */
    fun onConversationCreated(conversationId: String) {
        marks?.onConversationCreated(conversationId) { prefs.pins?.setPinned(it, true) }
    }

    /** A chat that is gone for good (an unsent new chat cleaned up) drops its pin and marks. */
    fun onConversationRemoved(conversationId: String) {
        marks?.forget(conversationId)
        prefs.pins?.setPinned(conversationId, false)
    }
}
