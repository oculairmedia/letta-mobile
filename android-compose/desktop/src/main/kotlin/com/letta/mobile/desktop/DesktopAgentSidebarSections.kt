package com.letta.mobile.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.lens.WorkPlayLens
import com.letta.mobile.desktop.chat.AgentOrb
import com.letta.mobile.desktop.chat.ConversationArchiveFilter
import com.letta.mobile.desktop.components.DesktopChipTab
import org.jetbrains.jewel.ui.component.PopupMenu as JewelPopupMenu

@Composable
internal fun SidebarAgentHeader(
    state: DesktopAgentSidebarState,
    actions: DesktopAgentSidebarActions,
) {
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
}

@Composable
internal fun SidebarNavSection(
    state: DesktopAgentSidebarState,
    actions: DesktopAgentSidebarActions,
) {
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
}

@Composable
internal fun ColumnScope.SidebarConversationList(
    state: DesktopAgentSidebarState,
    actions: DesktopAgentSidebarActions,
) {
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
