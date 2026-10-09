package com.letta.mobile.ui.shell.sidebar

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.PushPin
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Palette
import com.letta.mobile.ui.components.LettaListRow
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.shell.LocalShellChromeDecorations
import com.letta.mobile.ui.shell.ShellConfirmRequest
import com.letta.mobile.ui.shell.ShellConversationManageMenu
import com.letta.mobile.ui.shell.ShellConversationMenuActions
import com.letta.mobile.ui.shell.ShellRowMenus
import com.letta.mobile.ui.theme.LettaDimens

/** What a conversation row can do besides open; a null rename or pin is one the host does not offer. */
data class ShellConversationRowActions(
    val onClick: () -> Unit,
    val onArchiveToggle: () -> Unit,
    val onDelete: () -> Unit,
    val onRename: ((String) -> Unit)? = null,
    val onPinToggle: (() -> Unit)? = null,
    /** The backend has no delete command, so [onDelete] archives; the confirm dialog says so. */
    val deleteArchives: Boolean = false,
)

/** The delete confirm dialog's wording: honest about whether delete is permanent on this backend. */
internal object ShellDeleteConversationCopy {
    fun request(title: String, archives: Boolean): ShellConfirmRequest =
        if (archives) {
            ShellConfirmRequest(
                title = "Archive chat?",
                message = "\"$title\" will be archived and hidden from your chats. It isn't permanently deleted.",
                confirmLabel = "Archive",
            )
        } else {
            ShellConfirmRequest(
                title = "Delete chat?",
                message = "\"$title\" will be permanently removed. This can't be undone.",
                confirmLabel = "Delete",
            )
        }
}

/**
 * One conversation: icon, title over a one-line preview, a pin when pinned, and its time. While its
 * agent works the icon pulses; on hover it becomes a one-click archive (or restore). The row menu
 * (desktop: right-click, touch: long-press) offers rename, pin, archive and delete; rename edits
 * the title in place, and delete asks first.
 */
@Composable
fun ShellConversationRow(model: ShellConversationRowModel, actions: ShellConversationRowActions) {
    val decorations = LocalShellChromeDecorations.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val onRename = actions.onRename
    if (renaming && onRename != null) {
        ShellConversationRenameField(title = model.title, onRename = onRename, onDone = { renaming = false })
        return
    }
    val menuItems = ShellRowMenus.conversation(
        archived = model.archived,
        deleting = model.deleting,
        actions = ShellConversationMenuActions(
            onArchiveToggle = actions.onArchiveToggle,
            onRequestDelete = { confirmDelete = true },
            manage = ShellConversationManageMenu(
                pinned = model.pinned,
                onRenameRequest = onRename?.let { { renaming = true } },
                onPinToggle = actions.onPinToggle,
            ),
        ),
    )
    decorations.rowMenu(menuItems) {
        decorations.tooltip(model.title) {
            ShellConversationRowSurface(model, hovered, interactionSource, actions)
        }
    }
    if (confirmDelete) {
        decorations.confirm(
            ShellDeleteConversationCopy.request(model.title, actions.deleteArchives),
            {
                confirmDelete = false
                actions.onDelete()
            },
            { confirmDelete = false },
        )
    }
}

@Composable
private fun ShellConversationRowSurface(
    model: ShellConversationRowModel,
    hovered: Boolean,
    interactionSource: MutableInteractionSource,
    actions: ShellConversationRowActions,
) {
    Surface(
        onClick = actions.onClick,
        enabled = !model.deleting,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (model.selected) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        // Drives both the ripple (clipped to the shape) and `hovered`, so no separate hoverable.
        interactionSource = interactionSource,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.xs),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConversationLeadingIcon(model = model, hovered = hovered, onArchiveToggle = actions.onArchiveToggle)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (model.selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (model.deleting) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (model.preview.isNotBlank() && model.preview != model.title) {
                    Text(
                        text = model.preview,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = PREVIEW_ALPHA),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (model.pinned) {
                Icon(
                    imageVector = Icons.Outlined.PushPin,
                    contentDescription = "Pinned",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(PIN_ICON_SIZE).testTag(ShellConversationTags.PINNED),
                )
            }
            Text(
                text = if (model.deleting) "Deleting…" else model.timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ConversationLeadingIcon(model: ShellConversationRowModel, hovered: Boolean, onArchiveToggle: () -> Unit) {
    when {
        model.deleting -> CircularProgressIndicator(
            modifier = Modifier.size(LettaDimens.Control.icon),
            strokeWidth = LettaDimens.Space.hair,
            color = MaterialTheme.colorScheme.primary,
        )
        hovered -> ArchiveToggleIcon(archived = model.archived, noun = "chat", onToggle = onArchiveToggle)
        else -> Icon(
            imageVector = if (model.thinking) Icons.Outlined.Autorenew else Icons.Outlined.ChatBubbleOutline,
            contentDescription = if (model.thinking) "thinking" else null,
            tint = conversationIconColor(thinking = model.thinking, selected = model.selected),
            modifier = Modifier.size(LettaDimens.Control.icon),
        )
    }
}

/** While its agent is thinking the icon pulses in the primary colour. */
@Composable
private fun conversationIconColor(thinking: Boolean, selected: Boolean): Color {
    val transition = rememberInfiniteTransition(label = "convThinking")
    val pulse by transition.animateFloat(
        initialValue = PULSE_MIN_ALPHA,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
        label = "convThinkingAlpha",
    )
    return when {
        thinking -> MaterialTheme.colorScheme.primary.copy(alpha = pulse)
        selected -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
private fun ArchiveToggleIcon(archived: Boolean, noun: String, onToggle: () -> Unit) {
    Icon(
        imageVector = if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
        contentDescription = if (archived) "Restore $noun" else "Archive $noun",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .size(LettaDimens.Control.icon)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle),
    )
}

/**
 * One canvas in the library: icon, title, last-edit time. On hover, or while the row has keyboard
 * focus, the icon becomes a one-click archive (or restore), as a conversation's does; the row menu
 * (desktop: right-click, touch: long-press) offers the same. A null [onArchiveToggle] (a host with
 * no canvas archive) leaves both out.
 */
@Composable
fun ShellCanvasRow(model: ShellCanvasRowModel, onClick: () -> Unit, onArchiveToggle: (() -> Unit)?) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    LocalShellChromeDecorations.current.rowMenu(ShellRowMenus.canvas(model.archived, onArchiveToggle)) {
        LettaListRow(
            spec = LettaListRowSpec(title = model.title, icon = Lucide.Palette, trailing = model.timeLabel, selected = model.selected),
            onClick = onClick,
            modifier = Modifier
                .hoverable(interaction)
                .onFocusChanged { focused = it.hasFocus },
            leading = {
                if (onArchiveToggle != null && (hovered || focused)) {
                    ArchiveToggleIcon(archived = model.archived, noun = "canvas", onToggle = onArchiveToggle)
                } else {
                    Icon(
                        imageVector = Lucide.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(LettaDimens.Control.icon),
                    )
                }
            },
        )
    }
}

private val PIN_ICON_SIZE = 12.dp
private const val PREVIEW_ALPHA = 0.78f
private const val PULSE_MIN_ALPHA = 0.4f
private const val PULSE_MS = 700
