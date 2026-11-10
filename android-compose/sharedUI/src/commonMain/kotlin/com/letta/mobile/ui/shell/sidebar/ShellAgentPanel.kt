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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.shell.sidemenu.PanelViews
import com.letta.mobile.ui.chat.AgentOrb
import com.letta.mobile.ui.components.LettaMenuItem
import com.letta.mobile.ui.components.LettaPopupMenu
import com.letta.mobile.ui.mascot.MascotSeat
import com.letta.mobile.ui.mascot.MascotSeatVacancy
import com.letta.mobile.ui.mascot.MascotStage
import com.letta.mobile.ui.mascot.mascotAtWork
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.theme.LettaDimens

/** Test tags for the agent panel's parts. */
object ShellAgentPanelTags {
    const val PANEL = "shell-agent-panel"
    const val AGENT_MENU = "shell-agent-panel-agent-menu"
    /** The focused agent's mascot (or its orb) at the head of the panel. */
    const val HERO = "shell-agent-panel-hero"
    /** The host's model-and-context card under the agent's name. */
    const val AGENT_CARD = "shell-agent-panel-agent-card"
    /** The roster of chats and canvases. */
    const val LIBRARY = "shell-agent-panel-library"
}

/**
 * The agent panel: the focused agent's header (its mascot large, the name beneath), the per-agent
 * sections (Memory, Schedules, Channels, Skills) and New chat, the pinned conversations under an
 * Active / Archived / All filter, the canvases, and Settings. The desktop shows it as the sidebar
 * beside the agent rail; a phone shows it in the navigation drawer. [modifier] sizes and paints it.
 *
 * [agentCard]  is drawn under the agent's name: the host's model-and-context
 * card. Null draws nothing there; it is never drawn on the Home page.
 *
 * Which parts show, and their order, is [LocalPanelLane]: the workspace fills it from the shell
 * layout's panel lane, so the sidebar and the drawer's panel follow the same lane. The panel frame keeps the agent menu's dialogs (so they open wherever the
 * header sits), draws Settings at the bottom wherever the lane lists it, and skips parts it does
 * not know; a repeated part is drawn once.
 */
@Composable
fun ShellAgentPanel(
    state: ShellAgentPanelState,
    actions: ShellAgentPanelActions,
    modifier: Modifier = Modifier,
    agentCard: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .testTag(ShellAgentPanelTags.PANEL)
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.lg),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
    ) {
        // The agent menu lives only while an agent is open, so its dialog closes on the way Home.
        var dialog by remember(state.home) { mutableStateOf(ShellPanelDialog.None) }
        val frame = remember(state, actions, agentCard) { ShellPanelFrame(state, actions, agentCard, onOpenDialog = { dialog = it }) }
        val lane = LocalPanelLane.current
        val parts = remember(lane) { lane.distinct().filter { it in panelParts } }
        parts.filterNot { it == PanelViews.SETTINGS }.forEach { view -> key(view) { panelParts.getValue(view)(this, frame) } }
        if (PanelViews.SETTINGS in parts) {
            // The library fills the height above Settings; without it a spacer keeps Settings at the bottom.
            if (PanelViews.LIBRARY !in parts) Spacer(Modifier.weight(1f))
            ShellPanelSettings(frame)
        }
        ShellPanelDialogs(dialog = dialog, state = state, actions = actions, onDismiss = { dialog = ShellPanelDialog.None })
    }
}

/**
 * The panel's parts in order ([PanelViews]), provided by the workspace from its layout's panel lane.
 * Outside a workspace it is the default panel.
 */
internal val LocalPanelLane = compositionLocalOf { PanelViews.Default }

/** What every panel part draws from; [onOpenDialog] opens one of the frame's dialogs. */
@Immutable
private class ShellPanelFrame(
    val state: ShellAgentPanelState,
    val actions: ShellAgentPanelActions,
    val agentCard: (@Composable () -> Unit)?,
    val onOpenDialog: (ShellPanelDialog) -> Unit,
)

/** Draws one panel part in the panel's column. */
private typealias ShellPanelPart = @Composable ColumnScope.(frame: ShellPanelFrame) -> Unit

private fun panelPart(part: ShellPanelPart): ShellPanelPart = part

/** The panel's parts by view ([PanelViews]). */
private val panelParts: Map<RuntimeViewId, ShellPanelPart> = mapOf(
    PanelViews.HEADER to panelPart { ShellPanelHeader(it) },
    PanelViews.SECTIONS to panelPart { ShellPanelSections(it.state, it.actions) },
    PanelViews.NEW_CHAT to panelPart { ShellPanelNewChat(it.state, it.actions) },
    PanelViews.LIBRARY to panelPart { ShellPanelLibrary(it.state, it.actions) },
    PanelViews.SETTINGS to panelPart { ShellPanelSettings(it) },
)

@Composable
private fun ShellPanelSettings(frame: ShellPanelFrame) {
    if (!frame.state.showSettings) return
    ShellNavRow(
        model = ShellNavRowModel(label = "Settings", icon = Icons.Outlined.Settings, selected = frame.state.settingsSelected),
        onClick = frame.actions.onOpenSettings,
    )
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
    LocalLettaHost.current.tooltip(model.tooltip ?: model.label) {
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
private fun ShellPanelHeader(frame: ShellPanelFrame) {
    val state = frame.state
    val agentCard = frame.agentCard
    Column(Modifier.fillMaxWidth().padding(start = LettaDimens.Space.hair, bottom = LettaDimens.Space.lg)) {
        Box(Modifier.fillMaxWidth()) {
            ShellPanelTitleSlot(state = state, onEditAgent = frame.actions.onEditAgent, modifier = Modifier.fillMaxWidth())
            if (!state.home) Box(Modifier.align(Alignment.TopEnd)) { ShellAgentOverflowMenu(frame) }
        }
        if (agentCard != null && !state.home) {
            Box(Modifier.fillMaxWidth().padding(top = LettaDimens.Space.sm).testTag(ShellAgentPanelTags.AGENT_CARD)) { agentCard() }
        }
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
        // On a phone (the layer honours each seat's rule) it follows the avatar's live-or-still rule,
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
private fun ShellAgentOverflowMenu(frame: ShellPanelFrame) {
    val state = frame.state
    val actions = frame.actions
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
            items = listOfNotNull(
                LettaMenuItem(label = "New chat", onClick = actions.onNewChat),
                LettaMenuItem(label = "Edit agent", onClick = actions.onEditAgent),
                LettaMenuItem(label = "Memory") { actions.onOpenSection(LensDestination.Memory) },
                LettaMenuItem(label = "Archived chats") { frame.onOpenDialog(ShellPanelDialog.Archived) },
                state.chatOptions?.let { LettaMenuItem(label = "Chat options") { frame.onOpenDialog(ShellPanelDialog.ChatOptions) } },
                LettaMenuItem(label = "Settings", onClick = actions.onOpenSettings),
            ),
        )
    }
}

/** The dialog the agent menu has open, if any; the panel frame holds it. */
private enum class ShellPanelDialog { None, Archived, ChatOptions }

@Composable
private fun ShellPanelDialogs(
    dialog: ShellPanelDialog,
    state: ShellAgentPanelState,
    actions: ShellAgentPanelActions,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        ShellPanelDialog.None -> Unit
        ShellPanelDialog.Archived -> ShellArchivedChatsDialog(
            rows = state.archivedConversations,
            onRestore = { id -> actions.onArchiveConversation(id, false) },
            onOpen = { id ->
                onDismiss()
                actions.onConversationSelected(id)
            },
            onDismiss = onDismiss,
        )
        ShellPanelDialog.ChatOptions -> state.chatOptions?.let { options ->
            ShellChatOptionsDialog(state = options, actions = actions.chatOptions, onDismiss = onDismiss)
        }
    }
}

/** The per-agent section rows for the lens. */
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
}

/** The New chat row. */
@Composable
private fun ShellPanelNewChat(state: ShellAgentPanelState, actions: ShellAgentPanelActions) {
    ShellNavRow(
        model = ShellNavRowModel(label = WorkPlayLens.newConversationLabel(state.mode), icon = Icons.Outlined.Edit, selected = false),
        onClick = actions.onNewChat,
    )
}
