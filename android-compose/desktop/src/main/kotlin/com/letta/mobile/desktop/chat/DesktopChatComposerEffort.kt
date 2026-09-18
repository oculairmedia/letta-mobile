package com.letta.mobile.desktop.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.letta.mobile.data.composer.ComposerEffort
import com.letta.mobile.ui.theme.LettaDimens

/**
 * Composer effort chip + popover (Penpot "Effort popover"): an OPTIONS section
 * with a Thinking toggle and an EFFORT section (Minimal … Max) with the selected
 * level checked. Effort levels come from the shared [ComposerEffort].
 */
internal data class ComposerEffortChipState(
    val effort: ComposerEffort,
    val thinking: Boolean,
)

internal data class ComposerEffortChipActions(
    val onEffortChange: (ComposerEffort) -> Unit,
    val onThinkingChange: (Boolean) -> Unit,
)

@Composable
internal fun ComposerEffortChip(
    state: ComposerEffortChipState,
    actions: ComposerEffortChipActions,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ComposerActionChip(label = state.effort.label, onClick = { open = !open })
        if (open) {
            EffortPopover(
                state = state,
                actions = ComposerEffortChipActions(
                    onEffortChange = { level ->
                        actions.onEffortChange(level)
                        open = false
                    },
                    onThinkingChange = actions.onThinkingChange,
                ),
                onDismiss = { open = false },
            )
        }
    }
}

@Composable
private fun EffortPopover(
    state: ComposerEffortChipState,
    actions: ComposerEffortChipActions,
    onDismiss: () -> Unit,
) {
    val positionProvider = ViewportClampedPopupPositionProvider(yOffsetPx = -6)
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = Modifier.width(230.dp),
            shape = RoundedCornerShape(LettaDimens.Radius.md),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = LettaDimens.Space.sm,
        ) {
            Column(modifier = Modifier.padding(vertical = LettaDimens.Space.sm)) {
                EffortThinkingSection(
                    thinking = state.thinking,
                    onThinkingChange = actions.onThinkingChange,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = LettaDimens.Alpha.hairline)),
                )
                EffortLevelsSection(
                    effort = state.effort,
                    onEffortChange = actions.onEffortChange,
                )
            }
        }
    }
}

@Composable
private fun EffortThinkingSection(
    thinking: Boolean,
    onThinkingChange: (Boolean) -> Unit,
) {
    EffortSectionHeader("Options")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Thinking",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = thinking, onCheckedChange = onThinkingChange)
    }
}

@Composable
private fun EffortLevelsSection(
    effort: ComposerEffort,
    onEffortChange: (ComposerEffort) -> Unit,
) {
    EffortSectionHeader("Effort")
    ComposerEffort.entries.forEach { level ->
        EffortLevelRow(
            level = level,
            selected = level == effort,
            onClick = { onEffortChange(level) },
        )
    }
}

@Composable
private fun EffortLevelRow(
    level: ComposerEffort,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = level.label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "Selected",
                tint = Color(0xFF00BFA5),
                modifier = Modifier.size(LettaDimens.Space.lg),
            )
        }
    }
}

@Composable
internal fun EffortSectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = LettaDimens.Space.lg, end = LettaDimens.Space.lg, top = LettaDimens.Space.sm, bottom = LettaDimens.Space.xs),
    )
}

/** A composer chip that opens a separate picker (vs. an inline dropdown). */
@Composable
internal fun ComposerActionChip(
    label: String,
    onClick: () -> Unit,
    leadingIcon: ImageVector? = null,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(LettaDimens.Radius.sm),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.xs),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(LettaDimens.Space.md),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(text = label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(LettaDimens.Space.lg),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
