package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.composer_command_options
import com.letta.mobile.sharedui.resources.composer_uninstall_command
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.chat.MentionPopup
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerCommand
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.surface.touchStyle
import com.letta.mobile.ui.components.LettaMenuItem
import com.letta.mobile.ui.components.LettaPopupMenu
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/**
 * Chooses [command] for the draft's `/` token: a filling command puts its text in the draft;
 * an action command clears the token and runs.
 */
internal fun chooseComposerCommand(
    composer: ChatComposerUiState,
    autocomplete: ComposerAutocompleteUi,
    actions: ChatActions,
    command: ChatComposerCommand,
) {
    actions.updateComposerText(draftAfterCommand(composer.text, autocomplete.commandToken, command))
    if (!command.fillsComposer) actions.runComposerCommand(command)
}

/** The `/` and `@` suggestion popups above the prompt, for whichever token is active. */
@Composable
internal fun ComposerSuggestions(
    composer: ChatComposerUiState,
    autocomplete: ComposerAutocompleteUi,
    actions: ChatActions,
) {
    val onChoose: (ChatComposerCommand) -> Unit = { chooseComposerCommand(composer, autocomplete, actions, it) }
    if (touchStyle()) {
        TouchCommandChips(commands = autocomplete.matchedCommands, onChoose = onChoose, onUninstall = actions::uninstallComposerCommand)
    } else {
        ComposerCommandSuggestions(
            commands = autocomplete.matchedCommands,
            onChoose = onChoose,
            onUninstall = actions::uninstallComposerCommand,
        )
    }
    val mentionToken = autocomplete.mentionToken ?: return
    if (autocomplete.mentionGroups.isEmpty()) return
    MentionPopup(
        groups = autocomplete.mentionGroups,
        onSelect = { mention -> actions.updateComposerText(draftAfterMention(composer.text, mentionToken, mention)) },
    )
}

/** The `/` command list. Lifted from desktop's ComposerCommandSuggestions, plus Android's uninstall. */
@Composable
private fun ComposerCommandSuggestions(
    commands: List<ChatComposerCommand>,
    onChoose: (ChatComposerCommand) -> Unit,
    onUninstall: (ChatComposerCommand) -> Unit,
) {
    if (commands.isEmpty()) return
    Surface(
        modifier = Modifier
            .widthIn(max = ChatColumnMaxWidth)
            .fillMaxWidth()
            .testTag(ComposerTestTags.COMMAND_SUGGESTIONS),
        shape = RoundedCornerShape(LettaDimens.Radius.md),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(vertical = LettaDimens.Space.xs)) {
            commands.forEach { command ->
                CommandSuggestionRow(command = command, onChoose = onChoose, onUninstall = onUninstall)
            }
        }
    }
}

@Composable
private fun CommandSuggestionRow(
    command: ChatComposerCommand,
    onChoose: (ChatComposerCommand) -> Unit,
    onUninstall: (ChatComposerCommand) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChoose(command) }
            .testTag(ComposerTestTags.COMMAND_ROW + command.id)
            .padding(start = LettaDimens.Space.lg, end = LettaDimens.Space.xs),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = slashed(command.label),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = LettaDimens.Space.sm),
        )
        Text(
            text = command.description,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (command.removable) CommandOverflow(command, onUninstall)
    }
}

@Composable
private fun CommandOverflow(command: ChatComposerCommand, onUninstall: (ChatComposerCommand) -> Unit) {
    var menuOpen by remember(command.id) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(LettaDimens.Control.iconButton)) {
            Icon(
                imageVector = LettaIcons.MoreVert,
                contentDescription = stringResource(Res.string.composer_command_options, command.label.removePrefix("/")),
                modifier = Modifier.size(LettaDimens.Control.iconSm),
            )
        }
        LettaPopupMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            items = listOf(
                LettaMenuItem(
                    label = stringResource(Res.string.composer_uninstall_command, command.label.removePrefix("/")),
                    icon = LettaIcons.Delete,
                    onClick = { onUninstall(command) },
                ),
            ),
        )
    }
}

/**
 * letta-mobile-bglj6.1.23: the phone's `/` suggestions, a port of the legacy composer's
 * SlashCommandSuggestionRow: a horizontal strip of chips above the bar instead of the pointer
 * host's list card. Installed (removable) commands read as tonal chips with a check and uninstall
 * from a long press; the rest are plain chips with a "+".
 */
@Composable
private fun TouchCommandChips(
    commands: List<ChatComposerCommand>,
    onChoose: (ChatComposerCommand) -> Unit,
    onUninstall: (ChatComposerCommand) -> Unit,
) {
    if (commands.isEmpty()) return
    LazyRow(
        modifier = Modifier.fillMaxWidth().testTag(ComposerTestTags.COMMAND_SUGGESTIONS),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        contentPadding = PaddingValues(vertical = LettaDimens.Space.xs),
    ) {
        items(items = commands, key = { it.id }) { command ->
            TouchCommandChip(command = command, onChoose = onChoose, onUninstall = onUninstall)
        }
    }
}

@Composable
private fun TouchCommandChip(
    command: ChatComposerCommand,
    onChoose: (ChatComposerCommand) -> Unit,
    onUninstall: (ChatComposerCommand) -> Unit,
) {
    var menuOpen by remember(command.id) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Box {
        Surface(
            modifier = Modifier
                .clip(RoundedCornerShape(LettaDimens.Radius.sm))
                .combinedClickable(
                    role = Role.Button,
                    onClick = { onChoose(command) },
                    onLongClick = if (command.removable) ({ menuOpen = true }) else null,
                )
                .testTag(ComposerTestTags.COMMAND_ROW + command.id),
            shape = RoundedCornerShape(LettaDimens.Radius.sm),
            color = if (command.removable) scheme.secondaryContainer else scheme.surface,
            contentColor = if (command.removable) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
            border = BorderStroke(LettaDimens.Stroke.hairline, scheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm),
                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (command.removable) LettaIcons.Check else LettaIcons.Add,
                    contentDescription = null,
                    modifier = Modifier.size(LettaDimens.Control.icon),
                )
                Text(text = slashed(command.label), style = MaterialTheme.typography.bodySmall)
            }
        }
        LettaPopupMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            items = listOf(
                LettaMenuItem(
                    label = stringResource(Res.string.composer_uninstall_command, command.label.removePrefix("/")),
                    icon = LettaIcons.Delete,
                    onClick = { onUninstall(command) },
                ),
            ),
        )
    }
}
