package com.letta.mobile.ui.shell.sidebar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.ui.components.choice.LettaSegment
import com.letta.mobile.ui.components.choice.LettaSegmentedChoice
import com.letta.mobile.ui.components.choice.LettaSegmentedControl
import com.letta.mobile.ui.components.foundation.LettaRowLabel
import com.letta.mobile.ui.components.foundation.LettaToggleRow
import com.letta.mobile.data.chat.display.ChatDisplayOptions
import com.letta.mobile.data.chat.display.ChatTextSize
import com.letta.mobile.ui.ambient.ThinkingGlowStyle
import com.letta.mobile.ui.components.foundation.LettaSectionLabel
import com.letta.mobile.ui.host.LettaAlertPane
import com.letta.mobile.ui.host.LettaAlertSpec
import com.letta.mobile.ui.host.LettaPaneButton
import com.letta.mobile.ui.host.LettaPaneButtons
import com.letta.mobile.ui.host.LettaPaneKind
import com.letta.mobile.ui.theme.LettaDimens

/**  (G91, G37): what the Chat options dialog shows. */
@Immutable
data class ShellChatOptionsState(
    val display: ChatDisplayOptions = ChatDisplayOptions.Default,
    val textSize: ChatTextSize = ChatTextSize.Medium,
    val autoPinNewChats: Boolean = false,
    /** Whether a chat is open, so there is a transcript to download. */
    val canDownloadTranscript: Boolean = false,
    /** Whether this host can pin a chat it starts; without it the "Pin new chats" switch is left out. */
    val offersAutoPin: Boolean = true,
)

/** The Chat options dialog's state for a window: display options, its text size at [fontScale], auto-pin, and whether a chat is open. */
fun shellChatOptionsState(
    display: ChatDisplayOptions,
    fontScale: Float,
    autoPin: Boolean,
    hasChat: Boolean,
): ShellChatOptionsState = ShellChatOptionsState(
    display = display,
    textSize = ChatTextSize.nearest(fontScale),
    autoPinNewChats = autoPin,
    canDownloadTranscript = hasChat,
)

/** What the Chat options dialog changes. Every callback defaults to a no-op; a null download hides it. */
data class ShellChatOptionsActions(
    val onDisplayChange: (ChatDisplayOptions) -> Unit = {},
    val onTextSizeChange: (ChatTextSize) -> Unit = {},
    val onAutoPinNewChatsChange: (Boolean) -> Unit = {},
    val onDownloadTranscript: (() -> Unit)? = null,
)

/** Test tags for the Chat options and Archived chats dialogs. */
object ShellChatOptionsTags {
    const val DIALOG = "shell-chat-options"
    const val REASONING = "shell-chat-options-reasoning"
    const val COMPACTIONS = "shell-chat-options-compactions"
    const val USAGE = "shell-chat-options-usage"
    const val AUTO_PIN = "shell-chat-options-auto-pin"
    const val DOWNLOAD = "shell-chat-options-download"
    const val ARCHIVED = "shell-archived-chats"

    const val TEXT_PREFIX = "shell-chat-options-text-"
    const val GLOW_PREFIX = "shell-chat-options-glow-"

    fun textSize(size: ChatTextSize) = TEXT_PREFIX + size.name

    fun glow(style: ThinkingGlowStyle) = GLOW_PREFIX + style.name

    fun restore(id: String) = "shell-archived-restore-$id"
}

/**
 * The chat's display options (reasoning, compactions, token usage, text
 * size, thinking glow), the list's auto-pin, and Download transcript. Switches for the on/off
 * choices, segmented rows for the text size and the glow's style; changes apply at once and the
 * dialog stays open.
 */
@Composable
fun ShellChatOptionsDialog(state: ShellChatOptionsState, actions: ShellChatOptionsActions, onDismiss: () -> Unit) {
    val download = actions.onDownloadTranscript?.takeIf { state.canDownloadTranscript }
    val buttons = LettaPaneButtons(
        confirm = LettaPaneButton("Done", onDismiss),
        extra = download?.let { LettaPaneButton("Download transcript", it, testTag = ShellChatOptionsTags.DOWNLOAD) },
    )
    val spec = LettaAlertSpec("Chat options", buttons, kind = LettaPaneKind.Dialog, testTag = ShellChatOptionsTags.DIALOG)
    LettaAlertPane(spec, onDismiss) { ShellChatOptionsContent(state, actions) }
}

@Composable
private fun ShellChatOptionsContent(state: ShellChatOptionsState, actions: ShellChatOptionsActions) {
    val display = state.display
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        LettaSectionLabel("Show in chat")
        LettaToggleRow(
            label = LettaRowLabel("Reasoning"),
            checked = display.showReasoning,
            onCheckedChange = { actions.onDisplayChange(display.copy(showReasoning = it)) },
            modifier = Modifier.testTag(ShellChatOptionsTags.REASONING),
        )
        LettaToggleRow(
            label = LettaRowLabel("Compactions"),
            checked = display.showCompactions,
            onCheckedChange = { actions.onDisplayChange(display.copy(showCompactions = it)) },
            modifier = Modifier.testTag(ShellChatOptionsTags.COMPACTIONS),
        )
        LettaToggleRow(
            label = LettaRowLabel("Token usage"),
            checked = display.showTokenUsage,
            onCheckedChange = { actions.onDisplayChange(display.copy(showTokenUsage = it)) },
            modifier = Modifier.testTag(ShellChatOptionsTags.USAGE),
        )
        LettaSectionLabel("Text size")
        SegmentedChoice(TEXT_SIZE_SEGMENTS, state.textSize, ShellChatOptionsTags.TEXT_PREFIX, actions.onTextSizeChange)
        LettaSectionLabel("Thinking glow")
        SegmentedChoice(GLOW_SEGMENTS, display.thinkingGlow, ShellChatOptionsTags.GLOW_PREFIX) { actions.onDisplayChange(display.copy(thinkingGlow = it)) }
        if (state.offersAutoPin) {
            LettaSectionLabel("Chats")
            LettaToggleRow(
                label = LettaRowLabel("Pin new chats"),
                checked = state.autoPinNewChats,
                onCheckedChange = actions.onAutoPinNewChatsChange,
                modifier = Modifier.testTag(ShellChatOptionsTags.AUTO_PIN),
            )
        }
    }
}

/**
 * One choice of a few options as a segmented row: the shared [LettaSegmentedControl], each segment
 * tagged `tagPrefix + the value's name`. No check mark, so four short labels fit a phone's dialog on
 * one line; the selected segment's fill says which is on.
 */
@Composable
private fun <T : Enum<T>> SegmentedChoice(segments: List<Segment<T>>, selected: T, tagPrefix: String, onSelect: (T) -> Unit) {
    LettaSegmentedControl(
        choice = LettaSegmentedChoice(
            segments = segments.map { LettaSegment(it.value.name, it.label) },
            selected = selected.name,
            showCheck = false,
            tagPrefix = tagPrefix,
        ),
        onSelect = { key -> segments.firstOrNull { it.value.name == key }?.let { onSelect(it.value) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** One segment of a [SegmentedChoice]: the value it picks and its label. */
@Immutable
private class Segment<T>(val value: T, val label: String)

private val TEXT_SIZE_SEGMENTS = ChatTextSize.entries.map { Segment(it, it.label) }
private val GLOW_SEGMENTS = ThinkingGlowStyle.entries.map { Segment(it, it.label) }
