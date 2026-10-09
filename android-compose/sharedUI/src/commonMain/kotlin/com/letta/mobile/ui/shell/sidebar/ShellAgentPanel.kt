package com.letta.mobile.ui.shell.sidebar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.lens.WorkPlayLens
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.ui.chat.AgentOrb
import com.letta.mobile.ui.components.LettaChipTab
import com.letta.mobile.ui.components.LettaEmptyHint
import com.letta.mobile.ui.components.LettaMenuItem
import com.letta.mobile.ui.components.LettaPopupMenu
import com.letta.mobile.ui.components.LettaSectionLabel
import com.letta.mobile.ui.mascot.MascotSeat
import com.letta.mobile.ui.mascot.MascotSeatVacancy
import com.letta.mobile.ui.mascot.MascotStage
import com.letta.mobile.ui.mascot.mascotAtWork
import com.letta.mobile.ui.shell.LocalShellChromeDecorations
import com.letta.mobile.ui.theme.LettaDimens

/** Test tags for the agent panel's parts. */
object ShellAgentPanelTags {
    const val PANEL = "shell-agent-panel"
    const val AGENT_MENU = "shell-agent-panel-agent-menu"
    /** The focused agent's mascot (or its orb) at the head of the panel. */
    const val HERO = "shell-agent-panel-hero"
}

/**
 * The agent panel: the focused agent's header (its mascot large, the name beneath), the per-agent
 * sections (Memory, Schedules, Channels, Skills) and New chat, the pinned conversations under an
 * Active / Archived / All filter, the canvases, and Settings. The desktop shows it as the sidebar
 * beside the agent rail; a phone shows it in the navigation drawer. [modifier] sizes and paints it.
 */
@Composable
fun ShellAgentPanel(
    state: ShellAgentPanelState,
    actions: ShellAgentPanelActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .testTag(ShellAgentPanelTags.PANEL)
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.lg),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
    ) {
        ShellPanelHeader(state = state, actions = actions)
        ShellPanelSections(state = state, actions = actions)
        ShellPanelLibrary(state = state, actions = actions)
        if (state.showSettings) {
            ShellNavRow(
                model = ShellNavRowModel(label = "Settings", icon = Icons.Outlined.Settings, selected = state.settingsSelected),
                onClick = actions.onOpenSettings,
            )
        }
    }
}

/** The icon a section row wears in [mode]. */
fun shellSectionIcon(mode: WorkPlayMode, destination: LensDestination): ImageVector = when (destination) {
    LensDestination.Memory -> Icons.Outlined.Psychology
    LensDestination.Schedules -> Icons.Outlined.Schedule
    LensDestination.Channels -> Icons.Outlined.Hub
    LensDestination.Skills -> if (mode == WorkPlayMode.Play) Icons.Outlined.Group else Icons.Outlined.Build
    LensDestination.Conversations -> Icons.Outlined.ChatBubbleOutline
}

/** A navigation row: icon and label, drawn raised when it is the open page. */
data class ShellNavRowModel(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val tooltip: String? = null,
)

@Composable
fun ShellNavRow(model: ShellNavRowModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val content = if (model.selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    LocalShellChromeDecorations.current.tooltip(model.tooltip ?: model.label) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(LettaDimens.Space.xxl)
                .clip(MaterialTheme.shapes.small)
                .background(if (model.selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = LettaDimens.Space.sm),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = model.icon, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon), tint = content)
            Text(
                text = model.label,
                style = MaterialTheme.typography.bodyMedium,
                color = content,
                fontWeight = if (model.selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The title slot, then the agent's menu. The slot is the focused agent (mascot + name, tap to edit),
 * or "Home" while the fleet page is open; the agent menu has nothing to act on then, so it hides.
 */
@Composable
private fun ShellPanelHeader(state: ShellAgentPanelState, actions: ShellAgentPanelActions) {
    Box(Modifier.fillMaxWidth().padding(start = LettaDimens.Space.hair, bottom = LettaDimens.Space.lg)) {
        ShellPanelTitleSlot(state = state, onEditAgent = actions.onEditAgent, modifier = Modifier.fillMaxWidth())
        if (!state.home) Box(Modifier.align(Alignment.TopEnd)) { ShellAgentOverflowMenu(actions = actions) }
    }
}

/** Cross-fades the agent identity and "Home" in one slot, the new title sliding up into place. */
@Composable
private fun ShellPanelTitleSlot(state: ShellAgentPanelState, onEditAgent: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = state.home,
        transitionSpec = {
            (slideInVertically(animationSpec = tween(TITLE_SWAP_MS)) { height -> height / 2 } + fadeIn(tween(TITLE_SWAP_MS))) togetherWith
                (slideOutVertically(animationSpec = tween(TITLE_SWAP_MS)) { height -> -height / 2 } + fadeOut(tween(TITLE_SWAP_MS)))
        },
        modifier = modifier,
        label = "shellPanelTitle",
    ) { home ->
        if (home) {
            Text(
                text = "Home",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            ShellAgentIdentity(agent = state.agent, onEditAgent = onEditAgent)
        }
    }
}

private const val TITLE_SWAP_MS = 180

@Composable
private fun ShellAgentIdentity(agent: ShellPanelAgent, onEditAgent: () -> Unit) {
    // The mascot's pencil badge and the name open the agent's settings; the menu keeps the rest.
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
    ) {
        // The seat is empty while the character stands elsewhere; an agent with no mascot keeps its orb.
        // Drawn in place (a phone has no transport layer) it follows the avatar's live-or-still rule,
        // so the hero is the same picture of the agent as its rail orb, chip and list row.
        MascotSeat(
            agentId = agent.agentId,
            stage = MascotStage.AGENT_PANE_HERO,
            size = if (agent.identity != null) LettaDimens.Orb.hero else LettaDimens.Space.xxl,
            modifier = Modifier.testTag(ShellAgentPanelTags.HERO),
            onEdit = onEditAgent,
            live = mascotAtWork(agent.agentId),
        ) { vacancy ->
            if (vacancy == MascotSeatVacancy.NO_MASCOT) {
                AgentOrb(index = agent.orbIndex, size = LettaDimens.Orb.md, cornerRadius = LettaDimens.Radius.sm)
            }
        }
        Text(
            text = agent.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(LettaDimens.Radius.sm))
                .clickable(onClick = onEditAgent)
                .padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.hair),
        )
    }
}

@Composable
private fun ShellAgentOverflowMenu(actions: ShellAgentPanelActions) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Icon(
            imageVector = Icons.Outlined.MoreVert,
            contentDescription = "Agent menu",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(LettaDimens.Control.icon)
                .clickable { menuOpen = true }
                .testTag(ShellAgentPanelTags.AGENT_MENU),
        )
        LettaPopupMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            items = listOf(
                LettaMenuItem(label = "New chat", onClick = actions.onNewChat),
                LettaMenuItem(label = "Edit agent", onClick = actions.onEditAgent),
                LettaMenuItem(label = "Memory") { actions.onOpenSection(LensDestination.Memory) },
                LettaMenuItem(label = "Settings", onClick = actions.onOpenSettings),
            ),
        )
    }
}

/** The per-agent section rows for the lens, then New chat. */
@Composable
private fun ShellPanelSections(state: ShellAgentPanelState, actions: ShellAgentPanelActions) {
    WorkPlayLens.navDestinations(state.mode).filterNot { it in state.hiddenSections }.forEach { destination ->
        ShellNavRow(
            model = ShellNavRowModel(
                label = WorkPlayLens.destinationLabel(state.mode, destination),
                icon = shellSectionIcon(state.mode, destination),
                selected = state.selectedSection == destination,
            ),
            onClick = { actions.onOpenSection(destination) },
        )
    }
    ShellNavRow(
        model = ShellNavRowModel(label = WorkPlayLens.newConversationLabel(state.mode), icon = Icons.Outlined.Edit, selected = false),
        onClick = actions.onNewChat,
    )
}

/** Pinned conversations under their filter, then the canvases, in one scrolling list. */
@Composable
private fun ColumnScope.ShellPanelLibrary(state: ShellAgentPanelState, actions: ShellAgentPanelActions) {
    LettaSectionLabel(WorkPlayLens.conversationsHeader(state.mode))
    Row(
        modifier = Modifier.padding(start = LettaDimens.Space.xs, top = LettaDimens.Space.hair, bottom = LettaDimens.Space.sm),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        ShellArchiveFilter.entries.forEach { filter ->
            LettaChipTab(text = filter.label, active = state.archiveFilter == filter, onClick = { actions.onArchiveFilterChange(filter) })
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
    ) {
        items(items = state.conversations, key = { it.id }) { conversation ->
            ShellConversationRow(
                model = conversation,
                actions = ShellConversationRowActions(
                    onClick = { actions.onConversationSelected(conversation.id) },
                    onArchiveToggle = { actions.onArchiveConversation(conversation.id, !conversation.archived) },
                    onDelete = { actions.onDeleteConversation(conversation.id) },
                    deleteArchives = actions.deleteArchivesConversation,
                    onRename = actions.onRenameConversation?.let { rename -> { title -> rename(conversation.id, title) } },
                    onPinToggle = actions.onPinConversation?.let { pin -> { pin(conversation.id, !conversation.pinned) } },
                ),
            )
        }
        if (state.conversations.isEmpty()) item { LettaEmptyHint("No chats") }
        item { LettaSectionLabel("Canvases") }
        items(items = state.canvases, key = { "canvas-" + it.id.value }) { canvas ->
            ShellCanvasRow(
                model = canvas,
                onClick = { actions.onOpenCanvas(canvas.id) },
                onArchiveToggle = actions.onArchiveCanvas?.let { archive -> { archive(canvas.id, !canvas.archived) } },
            )
        }
        if (state.canvases.isEmpty()) item { LettaEmptyHint("No canvases") }
    }
}
