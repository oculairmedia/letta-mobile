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
import com.letta.mobile.desktop.chat.DesktopConversationSummary
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
        SidebarAgentIdentity(
            agentOrbIndex = state.agentOrbIndex,
            agentName = state.agentName,
            onEditAgent = actions.onEditAgent,
            modifier = Modifier.weight(1f),
        )
        SidebarAgentOverflowMenu(actions = actions)
    }
}

@Composable
private fun SidebarAgentIdentity(
    agentOrbIndex: Int,
    agentName: String,
    onEditAgent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Tapping the agent (orb + name) opens its Edit Agent settings; the
    // ⋮ menu keeps the other actions.
    Row(
        modifier = modifier
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
}

@Composable
private fun SidebarAgentOverflowMenu(actions: DesktopAgentSidebarActions) {
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
            SidebarAgentOverflowPopup(
                onDismiss = { menuOpen = false },
                actions = actions,
            )
        }
    }
}

@Composable
private fun SidebarAgentOverflowPopup(
    onDismiss: () -> Unit,
    actions: DesktopAgentSidebarActions,
) {
    JewelPopupMenu(
        onDismissRequest = {
            onDismiss()
            true
        },
        horizontalAlignment = Alignment.End,
    ) {
        selectableItem(
            selected = false,
            onClick = { onDismiss(); actions.onNewChat() },
        ) {
            DesktopControlText("New chat")
        }
        selectableItem(
            selected = false,
            onClick = { onDismiss(); actions.onEditAgent() },
        ) {
            DesktopControlText("Edit agent")
        }
        selectableItem(
            selected = false,
            onClick = {
                onDismiss()
                actions.onDestinationSelected(DesktopDestination.Memory)
            },
        ) {
            DesktopControlText("Memory")
        }
        selectableItem(
            selected = false,
            onClick = {
                onDismiss()
                actions.onDestinationSelected(DesktopDestination.Settings)
            },
        ) {
            DesktopControlText("Settings")
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
    SidebarArchiveFilterRow(
        archiveFilter = state.archiveFilter,
        onArchiveFilterChange = actions.onArchiveFilterChange,
    )
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(items = state.conversations, key = { it.id }) { conversation ->
            SidebarConversationListItem(
                conversation = conversation,
                state = state,
                actions = actions,
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
private fun SidebarArchiveFilterRow(
    archiveFilter: ConversationArchiveFilter,
    onArchiveFilterChange: (ConversationArchiveFilter) -> Unit,
) {
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
}

@Composable
private fun SidebarConversationListItem(
    conversation: DesktopConversationSummary,
    state: DesktopAgentSidebarState,
    actions: DesktopAgentSidebarActions,
) {
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
