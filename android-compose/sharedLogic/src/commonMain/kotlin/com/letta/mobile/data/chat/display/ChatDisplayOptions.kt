package com.letta.mobile.data.chat.display

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlin.math.abs

/**
 *  (G91): what the chat timeline shows besides the conversation itself. The
 * options are one global preference (every chat reads the same), kept on the client: the server
 * has no field for them.
 *
 * [showTokenUsage] covers the usage readout the chat draws (the composer's context meter); the
 * timeline has no per-turn usage row to hide.
 */
@Immutable
data class ChatDisplayOptions(
    val showReasoning: Boolean = true,
    val showCompactions: Boolean = true,
    val showTokenUsage: Boolean = true,
) {
    /** Whether [message] is left out of the timeline under these options. */
    fun hides(message: UiMessage): Boolean =
        (!showReasoning && message.isReasoning && message.subagentNotification == null) ||
            (!showCompactions && message.isCompaction)

    /** [messages] without the hidden ones; the same list when nothing is hidden. */
    fun visible(messages: List<UiMessage>): List<UiMessage> =
        if (showReasoning && showCompactions) messages else messages.filterNot(::hides)

    companion object {
        val Default = ChatDisplayOptions()
    }
}

/**
 * The chat's text size as three steps, laid over the existing continuous font scale (pinch-zoom
 * and Ctrl+scroll still set any value in the host's range). Picking a step sets its scale; the
 * selector shows the step nearest the current scale.
 */
enum class ChatTextSize(val label: String, val fontScale: Float) {
    Small("S", SMALL_SCALE),
    Medium("M", 1f),
    Large("L", LARGE_SCALE),
    ;

    companion object {
        /** The step closest to [scale]. */
        fun nearest(scale: Float): ChatTextSize = entries.minBy { abs(it.fontScale - scale) }
    }
}

private const val SMALL_SCALE = 0.9f
private const val LARGE_SCALE = 1.2f

/** [ChatDisplayOptions] kept in the settings store, so they survive a restart on both platforms. */
class ChatDisplayPrefs(
    private val store: SecureSettingsStore,
    private val keyPrefix: String = DEFAULT_PREFIX,
) {
    private val _options = MutableStateFlow(load())
    val options: StateFlow<ChatDisplayOptions> = _options.asStateFlow()

    fun update(transform: (ChatDisplayOptions) -> ChatDisplayOptions) {
        val next = _options.updateAndGet(transform)
        store.putString(key(REASONING), next.showReasoning.toString())
        store.putString(key(COMPACTIONS), next.showCompactions.toString())
        store.putString(key(USAGE), next.showTokenUsage.toString())
    }

    private fun load(): ChatDisplayOptions = ChatDisplayOptions(
        showReasoning = flag(REASONING),
        showCompactions = flag(COMPACTIONS),
        showTokenUsage = flag(USAGE),
    )

    private fun flag(name: String): Boolean = store.getString(key(name))?.toBooleanStrictOrNull() ?: true

    private fun key(name: String) = "$keyPrefix.$name"

    companion object {
        const val DEFAULT_PREFIX = "chat.display"
        private const val REASONING = "showReasoning"
        private const val COMPACTIONS = "showCompactions"
        private const val USAGE = "showTokenUsage"
    }
}
