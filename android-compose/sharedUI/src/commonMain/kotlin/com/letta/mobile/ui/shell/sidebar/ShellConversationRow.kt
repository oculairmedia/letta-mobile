package com.letta.mobile.ui.shell.sidebar

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Palette
import com.letta.mobile.data.chat.runtime.ConversationDeleteBehavior
import com.letta.mobile.ui.chat.branch.ForkNameDialog
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.components.LettaMenuItem
import com.letta.mobile.ui.components.LettaPopupMenu
import com.letta.mobile.ui.components.list.RosterListRow
import com.letta.mobile.ui.components.list.RosterRowSlots
import com.letta.mobile.ui.components.list.RosterRowState
import com.letta.mobile.ui.host.LettaConfirmPane
import com.letta.mobile.ui.host.LettaConfirmRequest
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.shell.ShellConversationManageMenu
import com.letta.mobile.ui.shell.ShellConversationMenuActions
import com.letta.mobile.ui.shell.ShellRowMenus
import com.letta.mobile.ui.theme.LettaDimens

/** What a conversation row can do besides open (the roster's selection opens it); a null rename or pin is one the host does not offer. */
data class ShellConversationRowActions(
    val onArchiveToggle: () -> Unit,
    val onDelete: () -> Unit,
    val onRename: ((String) -> Unit)? = null,
    val onPinToggle: (() -> Unit)? = null,
    /** What [onDelete] really does on this backend; the confirm dialog and the row menu follow it. */
    val deleteBehavior: ConversationDeleteBehavior = ConversationDeleteBehavior.Permanent,
    /** Settle and marker; null leaves both out of the menu. */
    val marks: ShellConversationMarkActions? = null,
    /**  (G37): fork the chat under the given name (null keeps the backend's title); null hides it. */
    val onFork: ((name: String?) -> Unit)? = null,
)

/** The delete confirm dialog's wording: honest about what delete does on this backend. */
internal object ShellDeleteConversationCopy {
    fun request(title: String, behavior: ConversationDeleteBehavior): LettaConfirmRequest =
        when (behavior) {
            ConversationDeleteBehavior.Permanent -> LettaConfirmRequest(
                title = "Delete chat?",
                message = "\"$title\" will be permanently removed. This can't be undone.",
                confirmLabel = "Delete",
            )
            ConversationDeleteBehavior.MovesToArchived -> LettaConfirmRequest(
                title = "Archive chat?",
                message = "\"$title\" will be moved to Archived. You can restore it from there.",
                confirmLabel = "Archive",
            )
            ConversationDeleteBehavior.RemovesFromLists -> LettaConfirmRequest(
                title = "Remove chat?",
                message = "\"$title\" will be removed from your chats and won't appear under Archived. " +
                    "Undo, offered right after, is the only way back.",
                confirmLabel = "Remove",
            )
        }
}

/**
 * What the conversation list has open, by conversation id: the delete confirmation, the marker
 * menu, the fork naming. One per list, so a row's menu (built by the roster) can open them.
 */
@Stable
class ShellConversationListUi {
    var confirmDeleteId by mutableStateOf<String?>(null)
    var markerId by mutableStateOf<String?>(null)
    var forkId by mutableStateOf<String?>(null)
}

/**
 * One conversation, drawn as a roster row: icon, title (after its marker) over a one-line preview
 * (or "Forked from …"), the settled and pinned marks, and its time. While its agent works the icon
 * pulses; on hover it becomes a one-click archive (or restore). Its menu is [shellConversationMenu],
 * opened by the roster (desktop: right-click, touch: long-press).
 */
@Composable
fun ShellConversationRow(
    model: ShellConversationRowModel,
    actions: ShellConversationRowActions,
    row: RosterRowState,
    ui: ShellConversationListUi,
) {
    Box {
        LocalLettaHost.current.tooltip(model.title) {
            RosterListRow(
                spec = LettaListRowSpec(
                    title = model.marker?.let { "$it ${model.title}" } ?: model.title,
                    selected = model.selected,
                    subtitle = model.subtitle.takeIf { it.isNotBlank() && it != model.title },
                    emphasized = model.selected,
                    quiet = model.deleting || model.settled,
                    enabled = !model.deleting,
                ),
                row = row,
                slots = RosterRowSlots(
                    leading = { ConversationLeadingIcon(model = model, hovered = row.hovered, onArchiveToggle = actions.onArchiveToggle) },
                    end = { ConversationRowEnd(model) },
                ),
            )
        }
        actions.marks?.let { marks -> ShellMarkerPicker(model, marks, ui) }
    }
}

/** The row's menu: rename, pin, fork, marker and settle when offered, then archive and delete. */
internal fun shellConversationMenu(
    model: ShellConversationRowModel,
    actions: ShellConversationRowActions,
    ui: ShellConversationListUi,
    row: RosterRowState,
): List<LettaMenuItem> {
    val marks = actions.marks
    return ShellRowMenus.conversation(
        archived = model.archived,
        deleting = model.deleting,
        deleteBehavior = actions.deleteBehavior,
        actions = ShellConversationMenuActions(
            onArchiveToggle = actions.onArchiveToggle,
            onRequestDelete = { ui.confirmDeleteId = model.id },
            manage = ShellConversationManageMenu(
                pinned = model.pinned,
                onRenameRequest = row.startRename.takeIf { actions.onRename != null },
                onPinToggle = actions.onPinToggle,
                settled = model.settled,
                onSettleToggle = marks?.let { { it.onSettle(model.id, !model.settled) } },
                onMarkerRequest = marks?.let { { ui.markerId = model.id } },
                onForkRequest = actions.onFork?.let { { ui.forkId = model.id } },
            ),
        ),
    )
}

/** The list's confirmation and fork naming, for whichever conversation asked. */
@Composable
internal fun ShellConversationDialogs(
    ui: ShellConversationListUi,
    rows: List<ShellConversationRowModel>,
    actionsFor: (ShellConversationRowModel) -> ShellConversationRowActions,
) {
    rows.firstOrNull { it.id == ui.confirmDeleteId }?.let { model ->
        val actions = actionsFor(model)
        LettaConfirmPane(
            ShellDeleteConversationCopy.request(model.title, actions.deleteBehavior),
            onConfirm = {
                ui.confirmDeleteId = null
                actions.onDelete()
            },
            onDismiss = { ui.confirmDeleteId = null },
        )
    }
    // letta-mobile-7mys7 (G37): "Fork chat…" asks for the fork's name, then forks.
    rows.firstOrNull { it.id == ui.forkId }?.let { model ->
        val fork = actionsFor(model).onFork ?: return@let
        ForkNameDialog(
            onFork = { name ->
                ui.forkId = null
                fork(name)
            },
            onDismiss = { ui.forkId = null },
        )
    }
}

/** The marker choices as a menu under the row, then "Clear marker" when the chat wears one. */
@Composable
private fun ShellMarkerPicker(model: ShellConversationRowModel, marks: ShellConversationMarkActions, ui: ShellConversationListUi) {
    val current = model.marker
    val pick = { marker: String? -> marks.onSetMarker(model.id, marker) }
    val items = marks.markerChoices.map { emoji ->
        LettaMenuItem(label = if (emoji == current) "$emoji  (current)" else emoji) { pick(emoji) }
    } + listOfNotNull(current?.let { LettaMenuItem(label = "Clear marker") { pick(null) } })
    LettaPopupMenu(
        expanded = ui.markerId == model.id,
        onDismiss = { ui.markerId = null },
        items = items,
        modifier = Modifier.testTag(ShellConversationTags.MARKER_PICKER),
    )
}

/** The settled and pinned marks, then the time (or "Deleting…"). */
@Composable
private fun ConversationRowEnd(model: ShellConversationRowModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm), verticalAlignment = Alignment.CenterVertically) {
        if (model.settled) RowMarkIcon(Icons.Outlined.TaskAlt, "Settled", ShellConversationTags.SETTLED)
        if (model.pinned) RowMarkIcon(Icons.Outlined.PushPin, "Pinned", ShellConversationTags.PINNED)
        Text(
            text = if (model.deleting) "Deleting…" else model.timeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** A small trailing mark (settled, pinned). */
@Composable
private fun RowMarkIcon(icon: ImageVector, description: String, tag: String) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(PIN_ICON_SIZE).testTag(tag),
    )
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
 * One canvas in the library, as a roster row: icon, title, last-edit time. On hover, or while the
 * row has keyboard focus, the icon becomes a one-click archive (or restore), as a conversation's
 * does; its menu ([ShellRowMenus.canvas]) offers the same. A null [onArchiveToggle] (a host with no
 * canvas archive) leaves both out.
 */
@Composable
fun ShellCanvasRow(model: ShellCanvasRowModel, row: RosterRowState, onArchiveToggle: (() -> Unit)?) {
    var focused by remember { mutableStateOf(false) }
    RosterListRow(
        spec = LettaListRowSpec(title = model.title, icon = Lucide.Palette, trailing = model.timeLabel, selected = model.selected),
        row = row,
        slots = RosterRowSlots(
            leading = {
                if (onArchiveToggle != null && (row.hovered || focused)) {
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
        ),
        modifier = Modifier.onFocusChanged { focused = it.hasFocus },
    )
}

private val PIN_ICON_SIZE = 12.dp
private const val PULSE_MIN_ALPHA = 0.4f
private const val PULSE_MS = 700
