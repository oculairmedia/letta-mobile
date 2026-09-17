package com.letta.mobile.desktop.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.composer.ComposerAutocomplete
import com.letta.mobile.data.context.ContextWindowUsageState
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.ui.chat.MentionPopup
import com.letta.mobile.ui.chat.ChatColumnMaxWidth

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
    val contextUsage: ContextWindowUsageState = ContextWindowUsageState(),
)

/** Callbacks for [ComposerBar] interactions. */
internal data class ComposerBarActions(
    val onModelSelected: (String) -> Unit,
    val onTextChanged: (String) -> Unit,
    val onSend: () -> Unit,
    val onAttachImage: () -> Unit,
    /** Composer plus menu: open the conversation's canvas beside the chat. Null hides the entry. */
    val onOpenCanvas: (() -> Unit)? = null,
    val onRemoveImageAttachment: (Int) -> Unit,
    val onOpenModelPicker: (() -> Unit)? = null,
)

@Composable
internal fun ComposerBar(
    state: ComposerBarState,
    actions: ComposerBarActions,
    modifier: Modifier = Modifier,
    /** The agent's live mascot, drawn at the input box's left edge so it sits with the text, not the pane. */
    companion: (@Composable () -> Unit)? = null,
) {
    val canSend = state.enabled &&
        (state.text.isNotBlank() || state.pendingImageAttachments.isNotEmpty())
    val autocomplete = composerAutocompleteUi(state)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 28.dp, top = 4.dp, end = 28.dp, bottom = 20.dp),
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
        // The box keeps its centred max width; the companion hangs off its left edge, so the
        // pair is centred together and the mascot stays beside the text at any pane width.
        // Without a companion the row is the column's width, so the box stays centred.
        val rowMaxWidth = if (companion != null) ChatColumnMaxWidth + ComposerCompanionSlot else ChatColumnMaxWidth
        Row(
            modifier = Modifier.widthIn(max = rowMaxWidth).fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (companion != null) {
                Box(
                    Modifier.width(ComposerCompanionSlot).padding(end = ComposerCompanionGap),
                    contentAlignment = Alignment.BottomCenter,
                ) { companion() }
            }
            Box(Modifier.weight(1f)) {
                ComposerInputSurface(
                    ComposerInputSurfaceParams(
                        state = state,
                        actions = actions,
                        canSend = canSend,
                        matchedCommands = autocomplete.matchedCommands,
                    ),
                )
            }
        }
        ComposerHintRow(
            visible = composerHintVisible(
                text = state.text,
                hasAttachments = state.pendingImageAttachments.isNotEmpty(),
            ),
        )
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

/** Width reserved for the composer companion (the mascot) at the box's left edge. */
internal val ComposerCompanionSlot = 108.dp

/** Breathing room between the mascot and the box's edge. */
private val ComposerCompanionGap = 16.dp
