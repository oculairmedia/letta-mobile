package com.letta.mobile.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.lens.WorkPlayLens
import com.letta.mobile.desktop.chat.ConversationArchiveFilter
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import com.letta.mobile.data.chat.runtime.displayTitle
import com.letta.mobile.desktop.components.DesktopChipTab
import com.letta.mobile.ui.mascot.MascotSeat
import com.letta.mobile.ui.mascot.MascotSeatVacancy
import com.letta.mobile.ui.mascot.MascotStage
import org.jetbrains.jewel.ui.component.PopupMenu as JewelPopupMenu
import com.letta.mobile.ui.components.LettaEmptyHint
import com.letta.mobile.ui.components.LettaListRow
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.components.LettaMenuItem
import com.letta.mobile.ui.components.LettaPopupMenu
import com.letta.mobile.ui.components.LettaSectionLabel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Palette
import com.letta.mobile.ui.chat.AgentOrb
import com.letta.mobile.ui.theme.LettaDimens

/**
 * Sidebar header: the title slot, then the agent's kebab.
 *
 * The title slot is shared — normally the focused agent (orb + name, tap to
 * edit), and "Home" while the fleet page is open. Home is entered from the
 * agent rail (fleet-wide controls live there), so everything in this sidebar
 * stays purely per-agent and the header is the agent's mascot alone.
 */
@Composable
internal fun SidebarAgentHeader(
    state: DesktopAgentSidebarState,
    actions: DesktopAgentSidebarActions,
) {
    val home = state.selectedDestination == DesktopDestination.Home
    // The mascot is the header: large, the name beneath it, the kebab tucked in the corner so
    // the character has the width to itself.
    Box(Modifier.fillMaxWidth().padding(start = LettaDimens.Space.hair, bottom = LettaDimens.Space.lg)) {
        SidebarHeaderTitleSlot(
            state = state,
            onEditAgent = actions.onEditAgent,
            modifier = Modifier.fillMaxWidth(),
        )
        // The kebab is the *agent's* menu; it has nothing to act on while Home
        // is showing, so it goes away with the agent identity.
        if (!home) Box(Modifier.align(Alignment.TopEnd)) { SidebarAgentOverflowMenu(actions = actions) }
    }
}

/**
 * Cross-fades the agent identity and the "Home" title in the same slot: the new
 * title slides up into place while the old one slides out, so the swap reads as
 * one control changing context rather than two widgets toggling.
 */
@Composable
private fun SidebarHeaderTitleSlot(
    state: DesktopAgentSidebarState,
    onEditAgent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = state.selectedDestination == DesktopDestination.Home,
        transitionSpec = {
            val duration = 180
            (
                slideInVertically(animationSpec = tween(duration)) { height -> height / 2 } +
                    fadeIn(animationSpec = tween(duration))
                ) togetherWith (
                slideOutVertically(animationSpec = tween(duration)) { height -> -height / 2 } +
                    fadeOut(animationSpec = tween(duration))
                )
        },
        modifier = modifier,
        label = "sidebarHeaderTitle",
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
            SidebarAgentIdentity(
                identity = SidebarIdentity(
                    agentOrbIndex = state.agentOrbIndex,
                    agentId = state.agentId,
                    agentIdentity = state.agentIdentity,
                    agentName = state.agentName,
                ),
                onEditAgent = onEditAgent,
            )
        }
    }
}

/** The hero seat's box; the character draws its body across ~60 % of it, so this reads as a ~75 dp mascot. */
private val SidebarHeroSeatSize = 124.dp

private data class SidebarIdentity(
    val agentOrbIndex: Int,
    val agentId: String?,
    val agentIdentity: com.letta.mobile.avatar.core.MascotIdentity?,
    val agentName: String,
)

@Composable
private fun SidebarAgentIdentity(
    identity: SidebarIdentity,
    onEditAgent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The mascot's pencil badge and the name open the Edit Agent settings; the
    // ⋮ menu keeps the other actions. Nothing larger than those is clickable.
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
    ) {
        // The agent pane's hero seat: the mascot stands here, large, whenever the pane shows.
        // The seat is empty while the character is away (the hop leaves nothing behind); an
        // agent with no mascot at all keeps its gradient orb.
        val mascot = identity.agentIdentity
        MascotSeat(
            agentId = identity.agentId,
            stage = MascotStage.AGENT_PANE_HERO,
            size = if (mascot != null) SidebarHeroSeatSize else LettaDimens.Space.xxl,
            onEdit = onEditAgent,
        ) { vacancy ->
            if (vacancy == MascotSeatVacancy.NO_MASCOT) AgentOrb(index = identity.agentOrbIndex, size = LettaDimens.Orb.md, cornerRadius = LettaDimens.Radius.sm)
        }
        Text(
            text = identity.agentName,
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
private fun SidebarAgentOverflowMenu(actions: DesktopAgentSidebarActions) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Icon(
            imageVector = Icons.Outlined.MoreVert,
            contentDescription = "Agent menu",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(LettaDimens.Control.icon)
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
    LettaPopupMenu(
        expanded = true,
        onDismiss = onDismiss,
        items = listOf(
            LettaMenuItem(label = "New chat", onClick = actions.onNewChat),
            LettaMenuItem(label = "Edit agent", onClick = actions.onEditAgent),
            LettaMenuItem(label = "Memory") { actions.onDestinationSelected(DesktopDestination.Memory) },
            LettaMenuItem(label = "Settings") { actions.onDestinationSelected(DesktopDestination.Settings) },
        ),
    )
}

@Composable
internal fun SidebarNavSection(
    state: DesktopAgentSidebarState,
    actions: DesktopAgentSidebarActions,
) {
    // Every row here is scoped to the focused agent; fleet-wide Home is reached
    // from the home icon in the header instead (see [SidebarAgentHeader]).
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
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
    ) {
        items(items = state.conversations, key = { it.id }) { conversation ->
            SidebarConversationListItem(
                conversation = conversation,
                state = state,
                actions = actions,
            )
        }
        if (state.conversations.isEmpty()) {
            item { SidebarEmptyHint("No chats") }
        }
        item {
            SidebarSection("Canvases")
        }
        items(items = state.canvases, key = { "canvas-" + it.id.value }) { canvas ->
            val archived = canvas.id in state.archivedCanvasIds
            SidebarCanvasListItem(
                canvas = canvas,
                selected = canvas.id == state.activeCanvasId,
                archived = archived,
                onClick = { actions.onOpenCanvas(canvas.id) },
                onArchiveToggle = { actions.onArchiveCanvas(canvas.id, !archived) },
            )
        }
        if (state.canvases.isEmpty()) {
            item { SidebarEmptyHint("No canvases") }
        }
    }
}

@Composable
private fun SidebarArchiveFilterRow(
    archiveFilter: ConversationArchiveFilter,
    onArchiveFilterChange: (ConversationArchiveFilter) -> Unit,
) {
    Row(
        modifier = Modifier.padding(start = LettaDimens.Space.xs, top = LettaDimens.Space.hair, bottom = LettaDimens.Space.sm),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
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
            title = conversation.displayTitle(),
            preview = conversation.lastMessagePreview
                .trim()
                .takeUnless { it.equals("Loaded from backend", ignoreCase = true) }
                .orEmpty(),
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
private fun SidebarEmptyHint(text: String) = LettaEmptyHint(text)

/**
 * One shared canvas in the sidebar library: icon, title, last-edit time. On hover the icon
 * becomes a one-click archive (or restore) button, as a conversation's does.
 */
@Composable
private fun SidebarCanvasListItem(
    canvas: com.letta.mobile.data.canvas.CanvasDocument,
    selected: Boolean,
    archived: Boolean,
    onClick: () -> Unit,
    onArchiveToggle: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LettaListRow(
        spec = LettaListRowSpec(
            title = canvas.title,
            icon = Lucide.Palette,
            trailing = formatRelativeTimestamp(java.time.Instant.ofEpochMilli(canvas.updatedAtEpochMs).toString()),
            selected = selected,
        ),
        onClick = onClick,
        modifier = Modifier.hoverable(interaction),
        leading = {
            if (hovered) {
                Icon(
                    imageVector = if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                    contentDescription = if (archived) "Restore canvas" else "Archive canvas",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(LettaDimens.Control.icon)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onArchiveToggle,
                        ),
                )
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

@Composable
private fun SidebarSection(label: String) = LettaSectionLabel(label)
