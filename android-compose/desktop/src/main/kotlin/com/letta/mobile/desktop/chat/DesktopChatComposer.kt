package com.letta.mobile.desktop.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.composer.AutocompleteTrigger
import com.letta.mobile.data.composer.ComposerAutocomplete
import com.letta.mobile.data.composer.ComposerEffort
import com.letta.mobile.data.composer.MentionCatalog
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.desktop.DesktopTextArea
import com.letta.mobile.desktop.DesktopTooltip
import com.letta.mobile.ui.theme.customColors

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
    // Trigger detection (/, @) is shared in commonMain so desktop and mobile
    // resolve autocomplete identically (ComposerAutocomplete).
    val activeToken = ComposerAutocomplete.activeToken(state.text)
    val commandToken = activeToken?.takeIf { it.trigger == AutocompleteTrigger.Command }
    val mentionToken = activeToken?.takeIf { it.trigger == AutocompleteTrigger.Mention }
    val matchedCommands = if (commandToken != null && state.commands.isNotEmpty()) {
        state.commands.filter { it.label.contains(commandToken.query, ignoreCase = true) }
    } else {
        emptyList()
    }
    val mentionGroups = if (mentionToken != null && state.mentionables.isNotEmpty()) {
        MentionCatalog.grouped(state.mentionables, mentionToken.query)
    } else {
        emptyList()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, top = 4.dp, end = 28.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ComposerCommandSuggestions(
            matchedCommands = matchedCommands,
            onCommandRun = { command ->
                actions.onTextChanged("")
                command.run()
            },
        )
        if (mentionGroups.isNotEmpty() && mentionToken != null) {
            MentionPopup(
                groups = mentionGroups,
                onSelect = { mention ->
                    actions.onTextChanged(
                        ComposerAutocomplete.replaceToken(
                            state.text,
                            mentionToken,
                            "@${mention.insertText} ",
                        ),
                    )
                },
            )
        }
        ComposerInputSurface(
            state = state,
            actions = actions,
            canSend = canSend,
            matchedCommands = matchedCommands,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp)
                .padding(start = 4.dp),
        ) {
            Text(
                text = "@ to add files   ·   / for commands   ·   ⏎ to send",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun ComposerCommandSuggestions(
    matchedCommands: List<ComposerCommand>,
    onCommandRun: (ComposerCommand) -> Unit,
) {
    if (matchedCommands.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            matchedCommands.take(8).forEach { command ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCommandRun(command) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "/${command.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = command.description,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerInputSurface(
    state: ComposerBarState,
    actions: ComposerBarActions,
    canSend: Boolean,
    matchedCommands: List<ComposerCommand>,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 760.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.pendingImageAttachments.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.pendingImageAttachments.forEachIndexed { index, image ->
                        ComposerAttachmentChip(
                            image = image,
                            onRemove = { actions.onRemoveImageAttachment(index) },
                        )
                    }
                }
            }
            DesktopTextArea(
                value = state.text,
                onValueChange = actions.onTextChanged,
                enabled = state.enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 28.dp, max = 120.dp)
                    .onPreviewKeyEvent { event ->
                        // Enter sends; Shift+Enter inserts a newline. Only an
                        // app-action command intercepts Enter — server slash
                        // commands (fillsComposer) let the message send.
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                            !event.isShiftPressed
                        ) {
                            val actionCommand = matchedCommands.firstOrNull { !it.fillsComposer }
                            if (actionCommand != null) {
                                actions.onTextChanged("")
                                actionCommand.run()
                                true
                            } else if (canSend) {
                                actions.onSend()
                                true
                            } else {
                                false
                            }
                        } else {
                            false
                        }
                    },
                maxLines = 5,
                placeholder = state.placeholder,
                undecorated = true,
            )
            ComposerControlRow(
                state = state,
                actions = actions,
                canSend = canSend,
            )
        }
    }
}

@Composable
private fun ComposerControlRow(
    state: ComposerBarState,
    actions: ComposerBarActions,
    canSend: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Plain Box button (not Jewel's IconButton, whose own height
        // metrics left it misaligned with the Surface chips beside it).
        DesktopTooltip(text = "Attach") {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(enabled = state.enabled, onClick = actions.onAttachImage),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "Attach",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val modelDisplay = state.modelOptions.firstOrNull { it.second == state.modelLabel }?.first
            ?: state.modelLabel.ifBlank { "Model" }
        if (actions.onOpenModelPicker != null) {
            // Rich, searchable, provider-grouped picker sheet.
            ComposerActionChip(label = modelDisplay, onClick = actions.onOpenModelPicker)
        } else {
            ComposerDropdownChip(
                model = ComposerDropdownChipModel(
                    label = modelDisplay,
                    options = state.modelOptions.map { it.first },
                    emptyHint = "No models available",
                    onSelect = { selected ->
                        state.modelOptions.firstOrNull { it.first == selected }
                            ?.let { actions.onModelSelected(it.second) }
                    },
                ),
            )
        }
        var safety by remember { mutableStateOf("Unrestricted") }
        ComposerDropdownChip(
            model = ComposerDropdownChipModel(
                label = safety,
                options = listOf("Unrestricted", "Standard", "Strict"),
                onSelect = { safety = it },
                leadingIcon = Icons.Outlined.Security,
                contentColor = MaterialTheme.customColors.runningColor
                    .takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.tertiary,
            ),
        )
        var effort by remember { mutableStateOf(ComposerEffort.Medium) }
        var thinking by remember { mutableStateOf(true) }
        ComposerEffortChip(
            state = ComposerEffortChipState(effort = effort, thinking = thinking),
            actions = ComposerEffortChipActions(
                onEffortChange = { effort = it },
                onThinkingChange = { thinking = it },
            ),
        )
        val grow = Modifier.weight(1f)
        Spacer(grow)
        Surface(
            onClick = actions.onSend,
            enabled = canSend,
            modifier = Modifier.size(38.dp),
            shape = CircleShape,
            color = if (canSend) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            contentColor = if (canSend) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.ArrowUpward,
                    contentDescription = "Send message",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Desktop-side UI mappings for ChatScreenStatus
// Icon, colour, copy, and retry affordance are all platform concerns kept
// here — only the MEANING of which state we are in moves to shared code.
// ---------------------------------------------------------------------------
