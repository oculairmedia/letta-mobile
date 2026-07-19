package com.letta.mobile.desktop.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.composer.ActiveToken
import com.letta.mobile.data.composer.AutocompleteTrigger
import com.letta.mobile.data.composer.ComposerAutocomplete
import com.letta.mobile.data.composer.ComposerEffort
import com.letta.mobile.data.composer.MentionCatalog
import com.letta.mobile.data.composer.MentionKind
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.desktop.DesktopTooltip
import com.letta.mobile.ui.theme.customColors

internal data class ComposerAutocompleteUi(
    val matchedCommands: List<ComposerCommand>,
    val mentionGroups: List<Pair<MentionKind, List<Mentionable>>>,
    val mentionToken: ActiveToken?,
)

internal fun composerAutocompleteUi(state: ComposerBarState): ComposerAutocompleteUi {
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
    return ComposerAutocompleteUi(
        matchedCommands = matchedCommands,
        mentionGroups = mentionGroups,
        mentionToken = mentionToken,
    )
}

@Composable
internal fun ComposerCommandSuggestions(
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
                ComposerCommandSuggestionRow(command = command, onCommandRun = onCommandRun)
            }
        }
    }
}

@Composable
private fun ComposerCommandSuggestionRow(
    command: ComposerCommand,
    onCommandRun: (ComposerCommand) -> Unit,
) {
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

@Composable
internal fun ComposerHintRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 760.dp)
            .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Text(
            text = "@ to add files   ·   / for commands   ·   ⏎ to send",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
        )
    }
}

internal data class ComposerInputSurfaceParams(
    val state: ComposerBarState,
    val actions: ComposerBarActions,
    val canSend: Boolean,
    val matchedCommands: List<ComposerCommand>,
)

@Composable
internal fun ComposerInputSurface(params: ComposerInputSurfaceParams) {
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
            ComposerPendingAttachmentsRow(params.state, params.actions)
            ComposerTextField(params)
            ComposerControlRow(
                state = params.state,
                actions = params.actions,
                canSend = params.canSend,
            )
        }
    }
}

@Composable
private fun ComposerPendingAttachmentsRow(
    state: ComposerBarState,
    actions: ComposerBarActions,
) {
    if (state.pendingImageAttachments.isEmpty()) return
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

@Composable
private fun ComposerTextField(params: ComposerInputSurfaceParams) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(params.state.text)) }
    LaunchedEffect(params.state.text) {
        if (params.state.text != fieldValue.text) {
            fieldValue = TextFieldValue(
                text = params.state.text,
                selection = TextRange(params.state.text.length),
            )
        }
    }
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        color = MaterialTheme.colorScheme.onSurface,
    )

    BasicTextField(
        value = fieldValue,
        onValueChange = { nextValue ->
            fieldValue = nextValue
            if (nextValue.text != params.state.text) {
                params.actions.onTextChanged(nextValue.text)
            }
        },
        enabled = params.state.enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 24.dp, max = 120.dp)
            .onPreviewKeyEvent { event ->
                composerEnterKeyHandled(
                    ComposerEnterKeyParams(
                        eventKey = event.key,
                        eventType = event.type,
                        shiftPressed = event.isShiftPressed,
                        matchedCommands = params.matchedCommands,
                        canSend = params.canSend,
                        onTextChanged = params.actions.onTextChanged,
                        onSend = params.actions.onSend,
                    ),
                )
            },
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        maxLines = 5,
        decorationBox = { innerTextField ->
            Box(modifier = Modifier.fillMaxWidth()) {
                if (fieldValue.text.isEmpty()) {
                    Text(
                        text = params.state.placeholder,
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                innerTextField()
            }
        },
    )
}

private data class ComposerEnterKeyParams(
    val eventKey: Key,
    val eventType: KeyEventType,
    val shiftPressed: Boolean,
    val matchedCommands: List<ComposerCommand>,
    val canSend: Boolean,
    val onTextChanged: (String) -> Unit,
    val onSend: () -> Unit,
)

private fun composerEnterKeyHandled(params: ComposerEnterKeyParams): Boolean {
    val isEnter = params.eventType == KeyEventType.KeyDown &&
        (params.eventKey == Key.Enter || params.eventKey == Key.NumPadEnter) &&
        !params.shiftPressed
    if (!isEnter) return false
    val actionCommand = params.matchedCommands.firstOrNull { !it.fillsComposer }
    return when {
        actionCommand != null -> {
            params.onTextChanged("")
            actionCommand.run()
            true
        }
        params.canSend -> {
            params.onSend()
            true
        }
        else -> false
    }
}

@Composable
internal fun ComposerControlRow(
    state: ComposerBarState,
    actions: ComposerBarActions,
    canSend: Boolean,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 540.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ComposerAttachButton(enabled = state.enabled, onAttachImage = actions.onAttachImage)
                    ComposerModelControls(state = state, actions = actions)
                    Spacer(Modifier.weight(1f))
                    ComposerSendButton(canSend = canSend, onSend = actions.onSend)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ComposerSafetyChip()
                    ComposerEffortControls()
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ComposerAttachButton(enabled = state.enabled, onAttachImage = actions.onAttachImage)
                ComposerModelControls(state = state, actions = actions)
                ComposerSafetyChip()
                ComposerEffortControls()
                Spacer(Modifier.weight(1f))
                ComposerSendButton(canSend = canSend, onSend = actions.onSend)
            }
        }
    }
}

@Composable
private fun ComposerAttachButton(enabled: Boolean, onAttachImage: () -> Unit) {
    DesktopTooltip(text = "Attach") {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(enabled = enabled, onClick = onAttachImage),
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
}

@Composable
private fun ComposerModelControls(
    state: ComposerBarState,
    actions: ComposerBarActions,
) {
    val modelDisplay = state.modelOptions.firstOrNull { it.second == state.modelLabel }?.first
        ?: state.modelLabel.ifBlank { "Model" }
    if (actions.onOpenModelPicker != null) {
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
}

@Composable
private fun ComposerSafetyChip() {
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
}

@Composable
private fun ComposerEffortControls() {
    var effort by remember { mutableStateOf(ComposerEffort.Medium) }
    var thinking by remember { mutableStateOf(true) }
    ComposerEffortChip(
        state = ComposerEffortChipState(effort = effort, thinking = thinking),
        actions = ComposerEffortChipActions(
            onEffortChange = { effort = it },
            onThinkingChange = { thinking = it },
        ),
    )
}

@Composable
private fun ComposerSendButton(canSend: Boolean, onSend: () -> Unit) {
    val containerColor by animateColorAsState(
        targetValue = if (canSend) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(durationMillis = 160),
        label = "composerSendContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (canSend) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        },
        animationSpec = tween(durationMillis = 160),
        label = "composerSendContent",
    )
    val scale by animateFloatAsState(
        targetValue = if (canSend) 1f else 0.9f,
        animationSpec = tween(durationMillis = 160),
        label = "composerSendScale",
    )
    Surface(
        onClick = onSend,
        enabled = canSend,
        modifier = Modifier
            .size(38.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
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
