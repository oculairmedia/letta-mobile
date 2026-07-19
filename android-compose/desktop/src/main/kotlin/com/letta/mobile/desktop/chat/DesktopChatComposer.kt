package com.letta.mobile.desktop.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.composer.ComposerAutocomplete
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.data.model.MessageContentPart

/** Read-only composer inputs (text, attachments, model catalog, autocomplete sources). */
internal data class ComposerBarState(
    val text: String,
    val pendingImageAttachments: List<MessageContentPart.Image>,
    val enabled: Boolean,
    val modelLabel: String,
    val modelOptions: List<Pair<String, String>>,
    val commands: List<ComposerCommand>,
    val mentionables: List<Mentionable>,
    val placeholder: String,
)

/** Callbacks for [ComposerBar] interactions. */
internal data class ComposerBarActions(
    val onModelSelected: (String) -> Unit,
    val onTextChanged: (String) -> Unit,
    val onSend: () -> Unit,
    val onAttachImage: () -> Unit,
    val onRemoveImageAttachment: (Int) -> Unit,
    val onOpenModelPicker: (() -> Unit)? = null,
)

@Composable
internal fun ComposerBar(
    state: ComposerBarState,
    actions: ComposerBarActions,
) {
    val canSend = state.enabled &&
        (state.text.isNotBlank() || state.pendingImageAttachments.isNotEmpty())
    val autocomplete = composerAutocompleteUi(state)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, top = 4.dp, end = 28.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ComposerCommandSuggestions(
            matchedCommands = autocomplete.matchedCommands,
            onCommandRun = { command ->
                actions.onTextChanged("")
                command.run()
            },
        )
        ComposerMentionSuggestions(
            autocomplete = autocomplete,
            composerText = state.text,
            onTextChanged = actions.onTextChanged,
        )
        ComposerInputSurface(
            ComposerInputSurfaceParams(
                state = state,
                actions = actions,
                canSend = canSend,
                matchedCommands = autocomplete.matchedCommands,
            ),
        )
        ComposerHintRow()
    }
}

@Composable
private fun ComposerMentionSuggestions(
    autocomplete: ComposerAutocompleteUi,
    composerText: String,
    onTextChanged: (String) -> Unit,
) {
    val mentionToken = autocomplete.mentionToken ?: return
    if (autocomplete.mentionGroups.isEmpty()) return
    MentionPopup(
        groups = autocomplete.mentionGroups,
        onSelect = { mention ->
            onTextChanged(
                ComposerAutocomplete.replaceToken(
                    composerText,
                    mentionToken,
                    "@${mention.insertText} ",
                ),
            )
        },
    )
}
