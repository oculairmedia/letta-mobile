package com.letta.mobile.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.lens.WorkPlayLens
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.desktop.chat.AgentOrb
import com.letta.mobile.desktop.chat.ConversationArchiveFilter
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
    state: DesktopAgentSidebarState,
    actions: DesktopAgentSidebarActions,
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
            mode = state.mode,
            onModeChange = actions.onModeChange,
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
                    .clickable(onClick = actions.onEditAgent),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AgentOrb(index = state.agentOrbIndex, size = 30.dp, cornerRadius = 6.dp)
                Text(
                    text = state.agentName,
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
                        selectableItem(
                            selected = false,
                            onClick = { menuOpen = false; actions.onNewChat() },
                        ) {
                            DesktopControlText("New chat")
                        }
                        selectableItem(
                            selected = false,
                            onClick = { menuOpen = false; actions.onEditAgent() },
                        ) {
                            DesktopControlText("Edit agent")
                        }
                        selectableItem(
                            selected = false,
                            onClick = {
                                menuOpen = false
                                actions.onDestinationSelected(DesktopDestination.Memory)
                            },
                        ) {
                            DesktopControlText("Memory")
                        }
                        selectableItem(
                            selected = false,
                            onClick = {
                                menuOpen = false
                                actions.onDestinationSelected(DesktopDestination.Settings)
                            },
                        ) {
                            DesktopControlText("Settings")
                        }
                    }
                }
            }
        }

        WorkPlayLens.navDestinations(state.mode).forEach { lensDestination ->
            val target = lensNavTarget(state.mode, lensDestination)
            DesktopNavRow(
                model = DesktopNavRowModel(
                    label = WorkPlayLens.destinationLabel(state.mode, lensDestination),
                    icon = target.second,
                    selected = state.selectedDestination == target.first,
                ),
                onClick = { actions.onDestinationSelected(target.first) },
            )
        }
        DesktopNavRow(
            model = DesktopNavRowModel(
                label = WorkPlayLens.newConversationLabel(state.mode),
                icon = Icons.Outlined.Edit,
                selected = false,
            ),
            onClick = actions.onNewChat,
        )

        // Pinned conversations / scenes.
        SidebarSection(WorkPlayLens.conversationsHeader(state.mode))
        // Active / Archived / All status filter.
        Row(
            modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ConversationArchiveFilter.entries.forEach { filter ->
                DesktopChipTab(text = filter.label, active = state.archiveFilter == filter) {
                    actions.onArchiveFilterChange(filter)
                }
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(items = state.conversations, key = { it.id }) { conversation ->
                SidebarConversationRow(
                    model = SidebarConversationRowModel(
                        title = conversation.title,
                        timeLabel = formatRelativeTimestamp(conversation.updatedAtLabel),
                        selected = state.selectedDestination == DesktopDestination.Conversations &&
                            conversation.id == state.selectedConversationId,
                        thinking = conversation.id == state.thinkingConversationId,
                        deleting = conversation.id in state.deletingConversationIds,
                        archived = conversation.archived,
                    ),
                    actions = SidebarConversationRowActions(
                        onClick = { actions.onConversationSelected(conversation.id) },
                        onArchiveToggle = {
                            actions.onArchiveConversation(conversation.id, !conversation.archived)
                        },
                        onDelete = { actions.onDeleteConversation(conversation.id) },
                    ),
                )
            }
            item {
                SidebarSection("Documents")
            }
            if (state.conversations.isEmpty()) {
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
            model = DesktopNavRowModel(
                label = "Settings",
                icon = Icons.Outlined.Settings,
                selected = state.selectedDestination == DesktopDestination.Settings,
            ),
            onClick = { actions.onDestinationSelected(DesktopDestination.Settings) },
        )
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
    model: DesktopNavRowModel,
    onClick: () -> Unit,
) {
    val content = when {
        model.selected -> MaterialTheme.colorScheme.onSurface
        model.subdued -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    DesktopTooltip(text = model.resolvedTooltip) {
        JewelSimpleListItem(
            selected = model.selected,
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
                    imageVector = model.icon,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = content,
                )
                JewelText(
                    text = model.label,
                    color = content,
                    fontWeight = if (model.selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
