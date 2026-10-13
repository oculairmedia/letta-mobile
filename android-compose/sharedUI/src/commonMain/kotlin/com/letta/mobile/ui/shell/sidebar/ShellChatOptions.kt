package com.letta.mobile.ui.shell.sidebar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.letta.mobile.data.chat.display.ChatDisplayOptions
import com.letta.mobile.data.chat.display.ChatTextSize
import com.letta.mobile.ui.components.LettaSectionLabel
import com.letta.mobile.ui.host.LettaAlertPane
import com.letta.mobile.ui.host.LettaAlertSpec
import com.letta.mobile.ui.host.LettaPaneButton
import com.letta.mobile.ui.host.LettaPaneButtons
import com.letta.mobile.ui.host.LettaPaneKind
import com.letta.mobile.ui.theme.LettaDimens

/** letta-mobile-zxk2n.5 (G91, G37): what the Chat options dialog shows. */
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

    fun textSize(size: ChatTextSize) = "shell-chat-options-text-${size.name}"

    fun restore(id: String) = "shell-archived-restore-$id"
}

/**
 * letta-mobile-zxk2n.5: the chat's display options (reasoning, compactions, token usage, text
 * size), the list's auto-pin, and Download transcript. Switches for the on/off choices, a
 * segmented S / M / L for the text size; changes apply at once and the dialog stays open.
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
        OptionSwitch("Reasoning", display.showReasoning, ShellChatOptionsTags.REASONING) {
            actions.onDisplayChange(display.copy(showReasoning = it))
        }
        OptionSwitch("Compactions", display.showCompactions, ShellChatOptionsTags.COMPACTIONS) {
            actions.onDisplayChange(display.copy(showCompactions = it))
        }
        OptionSwitch("Token usage", display.showTokenUsage, ShellChatOptionsTags.USAGE) {
            actions.onDisplayChange(display.copy(showTokenUsage = it))
        }
        LettaSectionLabel("Text size")
        TextSizeSelector(state.textSize, actions.onTextSizeChange)
        if (state.offersAutoPin) {
            LettaSectionLabel("Chats")
            OptionSwitch("Pin new chats", state.autoPinNewChats, ShellChatOptionsTags.AUTO_PIN, actions.onAutoPinNewChatsChange)
        }
    }
}

/** One on/off option: the whole row toggles, so the label is a target too. */
@Composable
private fun OptionSwitch(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = LettaDimens.Space.hair)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun TextSizeSelector(selected: ChatTextSize, onSelect: (ChatTextSize) -> Unit) {
    val sizes = ChatTextSize.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        sizes.forEachIndexed { index, size ->
            SegmentedButton(
                selected = size == selected,
                onClick = { onSelect(size) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = sizes.size),
                modifier = Modifier.testTag(ShellChatOptionsTags.textSize(size)),
            ) {
                Text(size.label)
            }
        }
    }
}
