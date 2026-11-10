package com.letta.mobile.ui.shell.rail

import com.letta.mobile.ui.components.LocalObjectVerbs

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.shell.sidemenu.RailViews
import com.letta.mobile.ui.chat.AgentOrb
import com.letta.mobile.ui.components.lettaFadingEdges
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.host.LettaHoverCard
import com.letta.mobile.ui.shell.ShellRowMenus
import com.letta.mobile.ui.theme.LettaDimens

/** Test tags for the rail's parts. */
object ShellAgentRailTags {
    const val RAIL = "shell-agent-rail"
    const val PIN_DIVIDER = "shell-agent-rail-pin-divider"
    fun orb(key: String) = "shell-agent-rail-orb-$key"
}

/**
 * The far-left agent rail: Home on top (fleet-wide, so never in the per-agent panel), the agents as
 * orbs (cached mascot stills where the agent has one), and New pinned at the bottom. Expanded, the header
 * controls gain labels and [library] replaces the orb list (the desktop's names-and-spaces library);
 * the icons keep their x either way.
 *
 * Which parts show, and their order, is [LocalRailLane]: the workspace fills it from the shell
 * layout's rail lane, so the wide rail and the drawer's rail follow the same lane. The rail keeps its width and expansion; the agents fill the height between
 * the other parts. A part the rail does not know draws nothing, and a repeated part is drawn once.
 */
@Composable
fun ShellAgentRail(
    state: ShellAgentRailState,
    actions: ShellAgentRailActions,
    modifier: Modifier = Modifier,
    library: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val width by animateDpAsState(
        if (state.expanded) LettaDimens.Pane.railExpandedWidth else LettaDimens.Pane.railWidth,
        label = "railWidth",
    )
    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .testTag(ShellAgentRailTags.RAIL)
            .padding(vertical = LettaDimens.Space.lg),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        val frame = remember(state, actions, library) { ShellRailFrame(state, actions, library) }
        val lane = LocalRailLane.current
        val parts = remember(lane) { lane.distinct().filter { it in railParts } }
        parts.forEach { view -> key(view) { railParts.getValue(view)(this, frame) } }
    }
}

/**
 * The rail's parts in order ([RailViews]), provided by the workspace from its layout's rail lane.
 * Outside a workspace it is the default rail.
 */
internal val LocalRailLane = compositionLocalOf { RailViews.Default }

/** What every rail part draws from: the rail's state, its actions and the expanded library. */
@Immutable
private class ShellRailFrame(
    val state: ShellAgentRailState,
    val actions: ShellAgentRailActions,
    val library: (@Composable ColumnScope.() -> Unit)?,
)

/** Draws one rail part in the rail's column. */
private typealias ShellRailPart = @Composable ColumnScope.(frame: ShellRailFrame) -> Unit

private fun railPart(part: ShellRailPart): ShellRailPart = part

/** The rail's parts by view ([RailViews]). */
private val railParts: Map<RuntimeViewId, ShellRailPart> = mapOf(
    RailViews.HOME to railPart { RailHomePart(it) },
    RailViews.AGENTS to railPart { RailAgentsPart(it) },
    RailViews.NEW to railPart { RailNewPart(it) },
)

@Composable
private fun RailHomePart(frame: ShellRailFrame) {
    val actions = frame.actions
    RailHeaderRow(onClick = actions.onHome, label = "Home".takeIf { frame.state.expanded }) {
        RailActionIcon(icon = Icons.Outlined.Home, description = "Home", selected = frame.state.homeSelected, onClick = actions.onHome)
    }
}

/**
 * The agents, filling the rail between its other parts: the orbs, or the library while expanded.
 * The drawer's rail has no library, so it always shows the orbs.
 */
@Composable
private fun ColumnScope.RailAgentsPart(frame: ShellRailFrame) {
    // The column's own spacing is the whole gap under Home: a spacer here would double it and
    // detach Home from the list it heads.
    Column(modifier = Modifier.weight(1f)) {
        val library = frame.library
        if (frame.state.expanded && library != null) {
            library()
        } else {
            ShellRailOrbList(entries = frame.state.entries, actions = frame.actions)
        }
    }
}

@Composable
private fun RailNewPart(frame: ShellRailFrame) {
    RailHeaderRow(onClick = frame.actions.onNewSession, label = "New".takeIf { frame.state.expanded }) {
        NewSessionButton(onNewSession = frame.actions.onNewSession)
    }
}

/**
 * A header control: the icon centred in a fixed rail-width slot so it keeps its x whether the rail
 * is collapsed or expanded; an optional [label] shows beside it and makes the whole row the control.
 */
@Composable
private fun RailHeaderRow(onClick: () -> Unit, label: String?, icon: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (label != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(LettaDimens.Pane.railWidth), contentAlignment = Alignment.Center) { icon() }
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The orbs, lazy and scrolling so a long roster never pushes New off-screen; the edges fade while it
 * scrolls. Agents the recents cut left out are reached through New, whose picker lists every agent.
 */
@Composable
private fun ColumnScope.ShellRailOrbList(entries: List<ShellRailEntry>, actions: ShellAgentRailActions) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .railScrollFades(listState),
        horizontalAlignment = Alignment.CenterHorizontally,
        // Each orb already sits in a slot with slack above and below; this spacing is on top of that.
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
    ) {
        // Keyed by name so each orb's thinking ring follows its agent across recency reordering.
        val pinned = entries.filter { it.pinned }
        val recent = entries.filterNot { it.pinned }
        items(pinned, key = { "orb-${it.key}" }) { entry ->
            ShellRailOrb(entry = entry, actions = actions)
        }
        if (pinned.isNotEmpty() && recent.isNotEmpty()) {
            item(key = "pin-divider") {
                HorizontalDivider(
                    modifier = Modifier.width(LettaDimens.Control.iconButton).testTag(ShellAgentRailTags.PIN_DIVIDER),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        items(recent, key = { "orb-${it.key}" }) { entry ->
            ShellRailOrb(entry = entry, actions = actions)
        }
    }
}

/** Fades whichever edge of the rail list has more to scroll to (about one orb's worth). */
@Composable
fun Modifier.railScrollFades(listState: LazyListState): Modifier {
    val top by animateFloatAsState(if (listState.canScrollBackward) 1f else 0f, tween(FADE_MS), label = "railTopFade")
    val bottom by animateFloatAsState(if (listState.canScrollForward) 1f else 0f, tween(FADE_MS), label = "railBottomFade")
    return lettaFadingEdges(top, bottom, LettaDimens.Space.xxl, LettaDimens.Space.xxl)
}

/** One orb: its hover card, and its agent menu (desktop: right-click, touch: long-press). */
@Composable
private fun ShellRailOrb(entry: ShellRailEntry, actions: ShellAgentRailActions) {
    val card = LettaHoverCard(
        title = entry.tooltip,
        timestamp = entry.activity?.updatedAtLabel,
        body = entry.activity?.preview?.trim()?.takeUnless { it.equals("Loaded from backend", ignoreCase = true) },
    )
    val host = LocalLettaHost.current
    host.rowMenu(ShellRowMenus.agent(entry, actions, LocalObjectVerbs.current)) {
        host.hoverCard(card) {
            Box(
                modifier = Modifier
                    .size(width = LettaDimens.Orb.railSlotWidth, height = LettaDimens.Orb.railSlotHeight)
                    .testTag(ShellAgentRailTags.orb(entry.key)),
                contentAlignment = Alignment.Center,
            ) {
                if (entry.selected) SelectedRailMarker(Modifier.align(Alignment.CenterStart))
                // Rail mascots stay on their cached still; thinking glows behind the character.
                if (entry.thinking) {
                    ShellThinkingAura(entry, size = LettaDimens.Orb.lg, modifier = Modifier.testTag("shell-thinking-aura-${entry.key}"))
                }
                ShellRailAgentTile(entry = entry, size = LettaDimens.Orb.lg, onClick = { actions.onAgentSelected(entry.agentId) })
            }
        }
    }
}

/** A rail tile: [AgentOrb] with its cached mascot still, so a rail never starts a live scene. */
@Composable
fun ShellRailAgentTile(
    entry: ShellRailEntry,
    size: Dp,
    cornerRadius: Dp = LettaDimens.Radius.sm,
    onClick: (() -> Unit)? = null,
) {
    AgentOrb(
        index = entry.orbStyle,
        size = size,
        cornerRadius = cornerRadius,
        onClick = onClick,
        agentId = entry.agentId,
        live = false,
        modifier = Modifier.semantics { contentDescription = entry.tooltip },
    ) {
        Text(
            text = entry.initial,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

@Composable
private fun SelectedRailMarker(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = LettaDimens.Space.xs, height = LettaDimens.Control.iconButton)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(LettaDimens.Radius.sm)),
    )
}

/** A rail control: [icon] on a circle, raised while it is the open page. */
@Composable
private fun RailActionIcon(icon: ImageVector, description: String, selected: Boolean, onClick: () -> Unit) {
    RailCircleButton(
        RailCircleButtonStyle(
            icon = icon,
            description = description,
            diameter = LettaDimens.Control.iconButtonLg,
            container = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        onClick = onClick,
    )
}

/** The rail's plus: opens the agent picker, whose top rows are the create actions. */
@Composable
private fun NewSessionButton(onNewSession: () -> Unit) {
    RailCircleButton(
        RailCircleButtonStyle(
            icon = Icons.Outlined.Add,
            description = "New",
            diameter = LettaDimens.Control.iconButton,
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        onClick = onNewSession,
    )
}

private data class RailCircleButtonStyle(
    val icon: ImageVector,
    val description: String,
    val diameter: Dp,
    val container: Color,
    val tint: Color,
)

@Composable
private fun RailCircleButton(style: RailCircleButtonStyle, onClick: () -> Unit) {
    LocalLettaHost.current.tooltip(style.description) {
        Box(
            modifier = Modifier
                .size(style.diameter)
                .clip(CircleShape)
                .background(style.container)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = style.icon,
                contentDescription = style.description,
                tint = style.tint,
                modifier = Modifier.size(LettaDimens.Control.icon),
            )
        }
    }
}

private const val FADE_MS = 250
