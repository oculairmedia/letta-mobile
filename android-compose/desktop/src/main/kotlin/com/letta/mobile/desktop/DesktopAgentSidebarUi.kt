package com.letta.mobile.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.lens.WorkPlayLens
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.desktop.chat.ConversationArchiveFilter
import com.letta.mobile.ui.shell.LocalShellChromeDecorations
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanel
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelActions
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState
import com.letta.mobile.ui.shell.sidebar.ShellArchiveFilter
import com.letta.mobile.ui.shell.sidebar.ShellCanvasRowModel
import com.letta.mobile.ui.shell.sidebar.ShellConversationMarks
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import com.letta.mobile.ui.shell.sidebar.ShellSidebarMapping
import com.letta.mobile.ui.shell.sidebar.shellSectionIcon
import com.letta.mobile.ui.theme.LettaDimens

/**
 * Agent sidebar (231.dp, #0D0D0D): the shared agent panel (letta-mobile-c3np7.2.3) - the active
 * agent header, per-agent navigation (Memory/Schedules/Channels/Skills/New chat), the pinned
 * conversation list and the canvases - with the desktop's tooltips, right-click menu and dialog.
 */
@Composable
internal fun DesktopAgentSidebar(
    state: DesktopAgentSidebarState,
    actions: DesktopAgentSidebarActions,
) {
    // Work | Play lens switcher (Penpot "App Mockups v2": top of sidebar).
    // Temporarily hidden — restore by rendering WorkPlaySwitcher above the panel. The lens itself
    // still drives the sidebar (the panel reads state.mode); only the toggle that changes it is hidden.
    CompositionLocalProvider(LocalShellChromeDecorations provides DesktopShellChromeDecorations) {
        ShellAgentPanel(
            state = state.toShellPanelState(),
            actions = actions.toShellPanelActions(state.mode),
            modifier = Modifier
                .width(231.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        )
    }
}

/** The desktop sidebar state as the shared panel's: relative times resolved, destinations as sections. */
internal fun DesktopAgentSidebarState.toShellPanelState(): ShellAgentPanelState = ShellAgentPanelState(
    agent = ShellPanelAgent(name = agentName, orbIndex = agentOrbIndex, agentId = agentId, identity = agentIdentity),
    mode = mode,
    home = selectedDestination == DesktopDestination.Home,
    selectedSection = WorkPlayLens.navDestinations(mode).firstOrNull { lensNavTarget(mode, it).first == selectedDestination },
    settingsSelected = selectedDestination == DesktopDestination.Settings,
    archiveFilter = archiveFilter.toShellArchiveFilter(),
    conversations = ShellSidebarMapping.conversationRows(
        conversations = conversations,
        marks = ShellConversationMarks(
            // A conversation reads as open only while the conversation page shows.
            selectedId = selectedConversationId.takeIf { selectedDestination == DesktopDestination.Conversations },
            thinkingId = thinkingConversationId,
            deletingIds = deletingConversationIds,
            pinnedIds = pinnedConversationIds,
        ),
        timeLabel = ::formatRelativeTimestamp,
    ),
    canvases = canvases.map { canvas ->
        ShellCanvasRowModel(
            id = canvas.id,
            title = canvas.title,
            timeLabel = formatRelativeTimestamp(java.time.Instant.ofEpochMilli(canvas.updatedAtEpochMs).toString()),
            selected = canvas.id == activeCanvasId,
            archived = canvas.id in archivedCanvasIds,
        )
    },
)

internal fun DesktopAgentSidebarActions.toShellPanelActions(mode: WorkPlayMode): ShellAgentPanelActions = ShellAgentPanelActions(
    onOpenSection = { onDestinationSelected(lensNavTarget(mode, it).first) },
    onOpenSettings = { onDestinationSelected(DesktopDestination.Settings) },
    onNewChat = onNewChat,
    onEditAgent = onEditAgent,
    onArchiveFilterChange = { onArchiveFilterChange(it.toConversationArchiveFilter()) },
    onConversationSelected = onConversationSelected,
    onArchiveConversation = onArchiveConversation,
    onDeleteConversation = onDeleteConversation,
    deleteBehavior = deleteBehavior,
    onRenameConversation = onRenameConversation,
    onPinConversation = onPinConversation,
    onOpenCanvas = onOpenCanvas,
    onArchiveCanvas = onArchiveCanvas,
)

internal fun ConversationArchiveFilter.toShellArchiveFilter(): ShellArchiveFilter = when (this) {
    ConversationArchiveFilter.Active -> ShellArchiveFilter.Active
    ConversationArchiveFilter.Archived -> ShellArchiveFilter.Archived
    ConversationArchiveFilter.All -> ShellArchiveFilter.All
}

internal fun ShellArchiveFilter.toConversationArchiveFilter(): ConversationArchiveFilter = when (this) {
    ShellArchiveFilter.Active -> ConversationArchiveFilter.Active
    ShellArchiveFilter.Archived -> ConversationArchiveFilter.Archived
    ShellArchiveFilter.All -> ConversationArchiveFilter.All
}

/** Maps a lens nav item to its concrete desktop destination + icon for the mode. */
internal fun lensNavTarget(
    mode: WorkPlayMode,
    destination: LensDestination,
): Pair<DesktopDestination, ImageVector> = when (destination) {
    LensDestination.Memory -> DesktopDestination.Memory
    LensDestination.Schedules -> DesktopDestination.Schedules
    LensDestination.Channels -> DesktopDestination.Channels
    LensDestination.Skills -> DesktopDestination.Agents
    LensDestination.Conversations -> DesktopDestination.Conversations
} to shellSectionIcon(mode, destination)

/**
 * Segmented Work | Play toggle (Penpot "App Mockups v2", top of the sidebar).
 *
 * Currently not rendered — see the note in [DesktopAgentSidebar].
 * Kept intact so restoring it is a one-line change.
 */
@Suppress("UnusedPrivateMember", "unused")
@Composable
private fun WorkPlaySwitcher(
    mode: WorkPlayMode,
    onModeChange: (WorkPlayMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(LettaDimens.Radius.md),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.padding(LettaDimens.Space.xs), horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
            WorkPlayMode.entries.forEach { option ->
                val selected = option == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(LettaDimens.Radius.sm))
                        .background(
                            if (selected) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent,
                        )
                        .clickable { onModeChange(option) }
                        .padding(vertical = LettaDimens.Space.sm),
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
