package com.letta.mobile.ui.shell.rail

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.chat.AgentOrb
import com.letta.mobile.ui.components.lettaFadingEdges
import com.letta.mobile.ui.shell.LocalShellChromeDecorations
import com.letta.mobile.ui.shell.ShellHoverCard
import com.letta.mobile.ui.shell.ShellRowMenus
import com.letta.mobile.ui.theme.LettaDimens

/** Test tags for the rail's parts. */
object ShellAgentRailTags {
    const val RAIL = "shell-agent-rail"
    fun orb(key: String) = "shell-agent-rail-orb-$key"
}

/**
 * The far-left agent rail: Home on top (fleet-wide, so never in the per-agent panel), the agents as
 * orbs (live mascots where the agent has one), and New pinned at the bottom. Expanded, the header
 * controls gain labels and [library] replaces the orb list (the desktop's names-and-spaces library);
 * the icons keep their x either way.
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
        RailHeaderRow(onClick = actions.onHome, label = "Home".takeIf { state.expanded }) {
            RailActionIcon(icon = Icons.Outlined.Home, description = "Home", selected = state.homeSelected, onClick = actions.onHome)
        }
        // The column's own spacing is the whole gap under Home: a spacer here would double it and
        // detach Home from the list it heads.
        Column(modifier = Modifier.weight(1f)) {
            if (state.expanded && library != null) {
                library()
            } else {
                ShellRailOrbList(entries = state.entries, actions = actions)
            }
        }
        RailHeaderRow(onClick = actions.onNewSession, label = "New".takeIf { state.expanded }) {
            NewSessionButton(onNewSession = actions.onNewSession)
        }
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

/** The orbs, lazy and scrolling so a long roster never pushes New off-screen; the edges fade while it scrolls. */
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
        items(entries, key = { "orb-${it.key}" }) { entry ->
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
    val card = ShellHoverCard(
        title = entry.tooltip,
        timestamp = entry.activity?.updatedAtLabel,
        body = entry.activity?.preview?.trim()?.takeUnless { it.equals("Loaded from backend", ignoreCase = true) },
    )
    val decorations = LocalShellChromeDecorations.current
    decorations.rowMenu(ShellRowMenus.agent(entry, actions)) {
        decorations.hoverCard(card) {
            Box(
                modifier = Modifier
                    .size(width = LettaDimens.Orb.railSlotWidth, height = LettaDimens.Orb.railSlotHeight)
                    .testTag(ShellAgentRailTags.orb(entry.key)),
                contentAlignment = Alignment.Center,
            ) {
                if (entry.selected) SelectedRailMarker(Modifier.align(Alignment.CenterStart))
                // A live mascot shows thinking itself; the ring is for the gradient orb only.
                if (entry.thinking && entry.identity == null) ShellThinkingRing(diameter = LettaDimens.Orb.md)
                ShellRailAgentTile(entry = entry, size = LettaDimens.Orb.lg, onClick = { actions.onAgentSelected(entry.agentId) })
            }
        }
    }
}

/** A rail tile: [AgentOrb] with the agent id, so it is the mascot when the agent has an identity. */
@Composable
fun ShellRailAgentTile(
    entry: ShellRailEntry,
    size: Dp,
    cornerRadius: Dp = LettaDimens.Radius.sm,
    onClick: (() -> Unit)? = null,
) {
    AgentOrb(index = entry.orbStyle, size = size, cornerRadius = cornerRadius, onClick = onClick, agentId = entry.agentId) {
        Text(text = entry.initial, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

/** Orbiting comet ring: the gradient orb's "thinking" indicator (continuous motion reads as working). */
@Composable
fun ShellThinkingRing(diameter: Dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = FULL_TURN,
        animationSpec = infiniteRepeatable(tween(ORBIT_MS, easing = LinearEasing)),
        label = "thinkingOrbit",
    )
    val primary = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.size(diameter)) {
        val strokeWidth = LettaDimens.Space.hair.toPx()
        val radius = (size.minDimension - strokeWidth) / 2f
        // A faint full track so it reads as a ring, then the tail building to the head at the seam.
        drawCircle(color = primary.copy(alpha = TRACK_ALPHA), radius = radius, style = Stroke(width = strokeWidth))
        rotate(angle) {
            drawCircle(
                brush = Brush.sweepGradient(0.0f to Color.Transparent, TAIL_START to Color.Transparent, 1.0f to primary),
                radius = radius,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            drawCircle(color = primary, radius = strokeWidth * HEAD_SCALE, center = Offset(center.x + radius, center.y))
        }
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
    LocalShellChromeDecorations.current.tooltip(style.description) {
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
private const val ORBIT_MS = 1200
private const val FULL_TURN = 360f
private const val TRACK_ALPHA = 0.18f
private const val TAIL_START = 0.35f
private const val HEAD_SCALE = 0.9f
