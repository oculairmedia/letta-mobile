package com.letta.mobile.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun SidebarConversationRow(
    model: SidebarConversationRowModel,
    actions: SidebarConversationRowActions,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    val container = if (model.selected) {
        MaterialTheme.colorScheme.surfaceContainer
    } else {
        Color.Transparent
    }
    val pulseAlpha = conversationPulseAlpha(thinking = model.thinking)
    val iconColor = conversationIconColor(
        thinking = model.thinking,
        selected = model.selected,
        pulseAlpha = pulseAlpha,
    )
    // Right-click anywhere on the row for the archive/restore + delete actions.
    ContextMenuArea(
        items = {
            sidebarConversationContextMenuItems(
                deleting = model.deleting,
                archived = model.archived,
                onArchiveToggle = actions.onArchiveToggle,
                onDeleteRequest = { confirmDelete = true },
            )
        },
    ) {
        Surface(
            onClick = actions.onClick,
            enabled = !model.deleting,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = container,
            contentColor = MaterialTheme.colorScheme.onSurface,
            // Drives both the click ripple (now clipped to the rounded shape) and the
            // `hovered` state below, so no separate .hoverable is needed.
            interactionSource = interactionSource,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SidebarConversationLeadingIcon(
                    model = SidebarConversationLeadingIconModel(
                        deleting = model.deleting,
                        hovered = hovered,
                        archived = model.archived,
                        thinking = model.thinking,
                        iconColor = iconColor,
                    ),
                    onArchiveToggle = actions.onArchiveToggle,
                )
                Text(
                    text = model.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (model.selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = conversationTitleColor(deleting = model.deleting),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (model.deleting) "Deleting…" else model.timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }

    if (confirmDelete) {
        DesktopConfirmDialog(
            request = ConfirmDialogRequest(
                title = "Delete chat?",
                message = "\"${model.title}\" will be permanently removed. This can't be undone.",
                confirmLabel = "Delete",
            ),
            onConfirm = {
                confirmDelete = false
                actions.onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** While thinking, the conversation icon pulses in the primary (teal) color. */
@Composable
private fun conversationPulseAlpha(thinking: Boolean): Float {
    val transition = rememberInfiniteTransition(label = "convThinking")
    val animated = transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "convThinkingAlpha",
    ).value
    return if (thinking) animated else 1f
}

@Composable
private fun conversationIconColor(
    thinking: Boolean,
    selected: Boolean,
    pulseAlpha: Float,
): Color = when {
    thinking -> MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
    selected -> MaterialTheme.colorScheme.onSurface
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun conversationTitleColor(deleting: Boolean): Color =
    if (deleting) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }

private fun sidebarConversationContextMenuItems(
    deleting: Boolean,
    archived: Boolean,
    onArchiveToggle: () -> Unit,
    onDeleteRequest: () -> Unit,
): List<ContextMenuItem> {
    if (deleting) return emptyList()
    return listOf(
        ContextMenuItem(if (archived) "Restore chat" else "Archive chat", onArchiveToggle),
        ContextMenuItem("Delete chat", onDeleteRequest),
    )
}

@Composable
private fun SidebarConversationLeadingIcon(
    model: SidebarConversationLeadingIconModel,
    onArchiveToggle: () -> Unit,
) {
    when {
        model.deleting -> CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        // On hover the leading icon becomes a one-click archive/restore button.
        model.hovered -> Icon(
            imageVector = if (model.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
            contentDescription = if (model.archived) "Restore chat" else "Archive chat",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(15.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onArchiveToggle,
                ),
        )
        else -> Icon(
            imageVector = if (model.thinking) {
                Icons.Outlined.Autorenew
            } else {
                Icons.Outlined.ChatBubbleOutline
            },
            contentDescription = if (model.thinking) "thinking" else null,
            tint = model.iconColor,
            modifier = Modifier.size(15.dp),
        )
    }
}
