package com.letta.mobile.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.lens.WorkPlayLens
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.desktop.chat.AgentOrb
import com.letta.mobile.desktop.chat.ConversationArchiveFilter
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import com.letta.mobile.desktop.components.DesktopChipTab
import org.jetbrains.jewel.ui.component.Icon as JewelIcon
import org.jetbrains.jewel.ui.component.PopupMenu as JewelPopupMenu
import org.jetbrains.jewel.ui.component.SimpleListItem as JewelSimpleListItem
import org.jetbrains.jewel.ui.component.Text as JewelText

/**
 * Agent sidebar (231.dp, #0D0D0D): the active agent header, per-agent
 * navigation (Memory/Schedules/Channels/Skills/New chat), then the pinned
 * conversation list and a Documents section.
 */
@Composable
internal fun DesktopAgentSidebar(
    agentName: String,
    agentOrbIndex: Int,
    conversations: List<DesktopConversationSummary>,
    selectedConversationId: String?,
    thinkingConversationId: String?,
    deletingConversationIds: Set<String> = emptySet(),
    archiveFilter: ConversationArchiveFilter,
    onArchiveFilterChange: (ConversationArchiveFilter) -> Unit,
    onArchiveConversation: (id: String, archived: Boolean) -> Unit,
    selectedDestination: DesktopDestination,
    mode: WorkPlayMode,
    onModeChange: (WorkPlayMode) -> Unit,
    onDestinationSelected: (DesktopDestination) -> Unit,
    onConversationSelected: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    onEditAgent: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(231.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Work | Play lens switcher (Penpot "App Mockups v2": top of sidebar).
        WorkPlaySwitcher(
            mode = mode,
            onModeChange = onModeChange,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
        // Agent header.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(start = 2.dp, bottom = 16.dp),
        ) {
            // Tapping the agent (orb + name) opens its Edit Agent settings; the
            // ⋮ menu keeps the other actions.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onEditAgent),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AgentOrb(index = agentOrbIndex, size = 30.dp, cornerRadius = 6.dp)
                Text(
                    text = agentName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "Agent menu",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { menuOpen = true },
                )
                if (menuOpen) {
                    JewelPopupMenu(
                        onDismissRequest = {
                            menuOpen = false
                            true
                        },
                        horizontalAlignment = Alignment.End,
                    ) {
                        selectableItem(selected = false, onClick = { menuOpen = false; onNewChat() }) {
                            DesktopControlText("New chat")
                        }
                        selectableItem(selected = false, onClick = { menuOpen = false; onEditAgent() }) {
                            DesktopControlText("Edit agent")
                        }
                        selectableItem(
                            selected = false,
                            onClick = { menuOpen = false; onDestinationSelected(DesktopDestination.Memory) },
                        ) {
                            DesktopControlText("Memory")
                        }
                        selectableItem(
                            selected = false,
                            onClick = { menuOpen = false; onDestinationSelected(DesktopDestination.Settings) },
                        ) {
                            DesktopControlText("Settings")
                        }
                    }
                }
            }
        }

        WorkPlayLens.navDestinations(mode).forEach { lensDestination ->
            val target = lensNavTarget(mode, lensDestination)
            DesktopNavRow(
                label = WorkPlayLens.destinationLabel(mode, lensDestination),
                icon = target.second,
                selected = selectedDestination == target.first,
                onClick = { onDestinationSelected(target.first) },
            )
        }
        DesktopNavRow(
            label = WorkPlayLens.newConversationLabel(mode),
            icon = Icons.Outlined.Edit,
            selected = false,
            onClick = onNewChat,
        )

        // Pinned conversations / scenes.
        SidebarSection(WorkPlayLens.conversationsHeader(mode))
        // Active / Archived / All status filter.
        Row(
            modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ConversationArchiveFilter.entries.forEach { filter ->
                DesktopChipTab(text = filter.label, active = archiveFilter == filter) {
                    onArchiveFilterChange(filter)
                }
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(items = conversations, key = { it.id }) { conversation ->
                SidebarConversationRow(
                    title = conversation.title,
                    timeLabel = formatRelativeTimestamp(conversation.updatedAtLabel),
                    selected = selectedDestination == DesktopDestination.Conversations &&
                        conversation.id == selectedConversationId,
                    thinking = conversation.id == thinkingConversationId,
                    deleting = conversation.id in deletingConversationIds,
                    archived = conversation.archived,
                    onClick = { onConversationSelected(conversation.id) },
                    onArchiveToggle = { onArchiveConversation(conversation.id, !conversation.archived) },
                    onDelete = { onDeleteConversation(conversation.id) },
                )
            }
            item {
                SidebarSection("Documents")
            }
            if (conversations.isEmpty()) {
                item {
                    Text(
                        text = "No chats",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    )
                }
            }
        }

        DesktopNavRow(
            label = "Settings",
            icon = Icons.Outlined.Settings,
            selected = selectedDestination == DesktopDestination.Settings,
            onClick = { onDestinationSelected(DesktopDestination.Settings) },
        )
    }
}

@Composable
private fun SidebarConversationRow(
    title: String,
    timeLabel: String,
    selected: Boolean,
    thinking: Boolean,
    deleting: Boolean,
    archived: Boolean,
    onClick: () -> Unit,
    onArchiveToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    val container = if (selected) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent
    // While thinking, the conversation icon pulses in the primary (teal) color.
    val pulseAlpha = if (thinking) {
        val transition = rememberInfiniteTransition(label = "convThinking")
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "convThinkingAlpha",
        ).value
    } else {
        1f
    }
    val iconColor = when {
        thinking -> MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
        selected -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // Right-click anywhere on the row for the archive/restore + delete actions.
    ContextMenuArea(
        items = {
            if (deleting) {
                emptyList()
            } else {
                listOf(
                    ContextMenuItem(if (archived) "Restore chat" else "Archive chat", onArchiveToggle),
                    ContextMenuItem("Delete chat") { confirmDelete = true },
                )
            }
        },
    ) {
        Surface(
            onClick = onClick,
            enabled = !deleting,
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
                when {
                    deleting -> CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    // On hover the leading icon becomes a one-click archive/restore button.
                    hovered -> Icon(
                        imageVector = if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                        contentDescription = if (archived) "Restore chat" else "Archive chat",
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
                        imageVector = if (thinking) Icons.Outlined.Autorenew else Icons.Outlined.ChatBubbleOutline,
                        contentDescription = if (thinking) "thinking" else null,
                        tint = iconColor,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (deleting) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (deleting) "Deleting…" else timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }

    if (confirmDelete) {
        DesktopConfirmDialog(
            title = "Delete chat?",
            message = "\"$title\" will be permanently removed. This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * Destructive-action confirmation as a real, separate desktop window (it "pops
 * out" of the app rather than dimming the page like a mobile sheet).
 */
@Composable
private fun DesktopConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dialogState = rememberDialogState(size = DpSize(420.dp, 210.dp))
    DialogWindow(
        onCloseRequest = onDismiss,
        state = dialogState,
        title = title,
        undecorated = true,
        resizable = false,
    ) {
        val windowScope = this
        DesktopMaterialTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Custom dark title bar — the native OS chrome is hidden.
                    with(windowScope) {
                        WindowDraggableArea(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(38.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(start = 16.dp),
                                )
                                Box(
                                    modifier = Modifier
                                        .size(width = 46.dp, height = 38.dp)
                                        .clickable(onClick = onDismiss),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Close",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    Column(
                        modifier = Modifier.fillMaxSize().padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        ) {
                            DesktopOutlinedButton(onClick = onDismiss) { DesktopButtonContent("Cancel") }
                            DesktopDefaultButton(onClick = onConfirm) { DesktopButtonContent(confirmLabel) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarSection(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp, start = 4.dp, bottom = 2.dp),
    )
}

/** Maps a lens nav item to its concrete desktop destination + icon for the mode. */
internal fun lensNavTarget(
    mode: WorkPlayMode,
    destination: LensDestination,
): Pair<DesktopDestination, ImageVector> = when (destination) {
    LensDestination.Memory -> DesktopDestination.Memory to Icons.Outlined.Psychology
    LensDestination.Schedules -> DesktopDestination.Schedules to Icons.Outlined.Schedule
    LensDestination.Channels -> DesktopDestination.Channels to Icons.Outlined.Hub
    LensDestination.Skills -> DesktopDestination.Agents to
        if (mode == WorkPlayMode.Play) Icons.Outlined.Group else Icons.Outlined.Build
    LensDestination.Conversations -> DesktopDestination.Conversations to Icons.Outlined.ChatBubbleOutline
}

/** Segmented Work | Play toggle (Penpot "App Mockups v2", top of the sidebar). */
@Composable
private fun WorkPlaySwitcher(
    mode: WorkPlayMode,
    onModeChange: (WorkPlayMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            WorkPlayMode.entries.forEach { option ->
                val selected = option == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent,
                        )
                        .clickable { onModeChange(option) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = WorkPlayLens.modeLabel(option),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopNavRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    subdued: Boolean = false,
    tooltip: String = label,
) {
    val content = when {
        selected -> MaterialTheme.colorScheme.onSurface
        subdued -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    DesktopTooltip(text = tooltip) {
        JewelSimpleListItem(
            selected = selected,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            height = 34.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                JewelIcon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = content,
                )
                JewelText(
                    text = label,
                    color = content,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
