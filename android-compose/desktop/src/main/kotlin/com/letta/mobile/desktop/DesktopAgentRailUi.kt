package com.letta.mobile.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.jewel.ui.component.TextField as JewelTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.agents.AgentRailGroup
import com.letta.mobile.data.agents.AgentRailSpace
import com.letta.mobile.data.agents.deriveAgentSpaces
import com.letta.mobile.desktop.chat.AgentOrb

/**
 * Format an ISO-8601 instant (e.g. lastMessageAt) as a compact relative label
 * (now / 5m / 2h / 4d / 3w / 2mo). Non-ISO values are returned unchanged.
 */
internal fun formatRelativeTimestamp(raw: String): String {
    val instant = runCatching { java.time.Instant.parse(raw) }.getOrNull() ?: return raw
    val seconds = java.time.Duration.between(instant, java.time.Instant.now()).seconds
    return when {
        seconds < 60 -> "now"
        seconds < 3_600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3_600}h"
        seconds < 604_800 -> "${seconds / 86_400}d"
        seconds < 2_592_000 -> "${seconds / 604_800}w"
        else -> "${seconds / 2_592_000}mo"
    }
}

@Composable
internal fun RailDivider() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** Pulsing teal ring used as the agent "thinking" indicator. */
@Composable
private fun ThinkingRing(
    diameter: Dp,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "thinkingAlpha",
    )
    Box(
        modifier = modifier
            .size(diameter)
            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = alpha), RoundedCornerShape(cornerRadius)),
    )
}

@Immutable
internal data class DesktopAgentRailFocus(
    val selectedAgentId: String?,
    val thinkingAgentId: String?,
    val avatarStyleByAgentId: Map<String, Int>,
)

@Immutable
internal data class DesktopAgentRailState(
    val agents: List<Pair<String, String>>,
    val focus: DesktopAgentRailFocus,
    /** Spotify-style expanded library mode: names + spaces, not just orbs. */
    val expanded: Boolean = false,
)

@Immutable
internal data class DesktopAgentRailActions(
    val onAgentSelected: (String) -> Unit,
    val onNewSession: () -> Unit,
    val onToggleExpanded: () -> Unit = {},
)

/**
 * Far-left workspace/agent rail (Penpot "App Mockups v2", 56.dp wide, #0A0A0A):
 * a "+" new-session button, a stack of gradient agent orbs (one per agent), and
 * search/settings/identity actions pinned to the bottom.
 */
@Composable
internal fun DesktopAgentRail(
    state: DesktopAgentRailState,
    actions: DesktopAgentRailActions,
) {
    // Collapse agents that share a display name — e.g. the many ephemeral
    // "Letta Code" agents spawned per task — into a single stacked orb with a
    // count chip, so the rail doesn't grow unbounded with near-duplicate spawns.
    // Order follows first appearance in [agents].
    val groups = remember(state.agents) {
        state.agents.groupBy { it.second }
            .map { (name, members) -> AgentRailGroup(name = name, agentIds = members.map { it.first }) }
    }
    val width by animateDpAsState(if (state.expanded) 248.dp else 56.dp, label = "railWidth")
    // Start-aligned in BOTH modes, with each header control wrapped in a
    // fixed 56dp-wide centering slot: expanding the rail must not shift the
    // icons horizontally — labels appear beside them, the icons stay put.
    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 15.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RailHeaderRow(onClick = actions.onToggleExpanded) {
            RailExpandToggle(expanded = state.expanded, onToggle = actions.onToggleExpanded)
        }
        RailHeaderRow(onClick = actions.onNewSession, label = if (state.expanded) "New session" else null) {
            NewSessionButton(onNewSession = actions.onNewSession)
        }
        if (!state.expanded) {
            // Collapsed library search just opens the library — the search
            // field lives inline in the expanded panel, Spotify-style. Lives
            // up top with the other actions, not below the roster.
            RailHeaderRow(onClick = actions.onToggleExpanded) {
                RailActionIcon(
                    RailActionIconModel(
                        icon = Icons.Outlined.Search,
                        description = "Search agents",
                        onClick = actions.onToggleExpanded,
                    ),
                )
            }
        }
        // Clear separation between the action block and the roster.
        Spacer(Modifier.height(14.dp))
        if (state.expanded) {
            ExpandedAgentLibrary(
                groups = groups,
                focus = state.focus,
                onAgentSelected = actions.onAgentSelected,
            )
        } else {
            AgentRailOrbList(
                groups = groups,
                focus = state.focus,
                onAgentSelected = actions.onAgentSelected,
            )
        }
    }
}

/**
 * Header control slot: the icon is centered inside a fixed 56dp column (the
 * collapsed rail width) so it occupies the same x whether the rail is
 * collapsed or expanded; an optional [label] reveals to the icon's right in
 * expanded mode. The whole row is clickable so the label acts as part of the
 * control.
 */
@Composable
private fun RailHeaderRow(
    onClick: () -> Unit,
    label: String? = null,
    icon: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (label != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(56.dp), contentAlignment = Alignment.Center) {
            icon()
        }
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

@Composable
private fun RailExpandToggle(expanded: Boolean, onToggle: () -> Unit) {
    RailActionIcon(
        RailActionIconModel(
            icon = Icons.Outlined.Menu,
            description = if (expanded) "Collapse agent library" else "Expand agent library",
            onClick = onToggle,
        ),
    )
}

/**
 * Spotify "Your Library"-style expanded rail: agents grouped into
 * Element-style spaces (derived from naming conventions), each section
 * headed by its aggregate impact — member count and a live working
 * indicator — rather than one anonymous orb per agent.
 */
@Composable
private fun ColumnScope.ExpandedAgentLibrary(
    groups: List<AgentRailGroup>,
    focus: DesktopAgentRailFocus,
    onAgentSelected: (String) -> Unit,
) {
    // Spotify-style in-panel filter: search never leaves the library.
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val filtered = remember(groups, query.text) {
        val needle = query.text.trim()
        if (needle.isEmpty()) groups else groups.filter { it.name.contains(needle, ignoreCase = true) }
    }
    val spaces = remember(filtered) { deriveAgentSpaces(filtered) }
    // Orb colors key off the UNfiltered position so identities stay stable
    // while filtering; precomputed map avoids O(n²) indexOf on big rosters.
    val indexByGroup = remember(groups) {
        groups.withIndex().associate { (index, group) -> group to index }
    }
    LibrarySearchField(query = query, onQueryChange = { query = it })
    // Lazy: a large roster (hundreds of agents) must not compose every row.
    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
        if (filtered.isEmpty()) {
            item(key = "library-empty") {
                Text(
                    text = "No agents match \"${query.text.trim()}\"",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
        spaces.forEach { space ->
            item(key = "space-${space.name}") {
                SpaceHeader(space = space, focus = focus)
            }
            // Keyed by group name so per-row state (the thinking-ring
            // animation) follows its agent across recency reordering.
            items(space.groups, key = { "group-${it.name}" }) { group ->
                ExpandedAgentRow(
                    params = AgentRailOrbParams(
                        group = group,
                        index = indexByGroup[group] ?: 0,
                        focus = focus,
                        onAgentSelected = onAgentSelected,
                    ),
                )
            }
        }
    }
}

@Composable
private fun LibrarySearchField(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        JewelTextField(
            value = query,
            onValueChange = onQueryChange,
            undecorated = true,
            placeholder = {
                Text(
                    text = "Search agents",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SpaceHeader(space: AgentRailSpace, focus: DesktopAgentRailFocus) {
    val working = focus.thinkingAgentId != null &&
        space.groups.any { focus.thinkingAgentId in it.agentIds }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = space.name.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (working) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
        Text(
            text = space.agentCount.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExpandedAgentRow(params: AgentRailOrbParams) {
    val flags = params.toFlags()
    val target = params.toTarget(flags)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = { params.onAgentSelected(target.agentId) })
            .background(
                if (flags.selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (flags.thinking) {
                ThinkingRing(diameter = 32.dp, cornerRadius = 9.dp)
            }
            AgentOrb(index = target.orbStyle, size = 28.dp, cornerRadius = 8.dp) {
                Text(
                    text = params.group.name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
        }
        Text(
            text = params.group.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (flags.selected) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // No per-row member count: PM groups aggregate hundreds of spawns, so
        // the number was always a meaningless "99+" — the space header already
        // carries the aggregate.
    }
}

@Composable
private fun ColumnScope.AgentRailOrbList(
    groups: List<AgentRailGroup>,
    focus: DesktopAgentRailFocus,
    onAgentSelected: (String) -> Unit,
) {
    // The agent list scrolls so a long roster never pushes the bottom
    // actions off-screen, and is lazy so hundreds of agents don't all
    // compose. Keyed by group name so each row's thinking-ring animation
    // follows its agent across recency reordering.
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(groups, key = { _, group -> "orb-${group.name}" }) { index, group ->
            AgentRailOrb(
                AgentRailOrbParams(
                    group = group,
                    index = index,
                    focus = focus,
                    onAgentSelected = onAgentSelected,
                ),
            )
        }
    }
}


@Composable
private fun NewSessionButton(onNewSession: () -> Unit) {
    DesktopTooltip(text = "New session") {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onNewSession),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = "New session",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private data class AgentRailOrbParams(
    val group: AgentRailGroup,
    val index: Int,
    val focus: DesktopAgentRailFocus,
    val onAgentSelected: (String) -> Unit,
)

private data class AgentRailOrbFlags(
    val selected: Boolean,
    val thinking: Boolean,
    val count: Int,
)

private data class AgentRailOrbTarget(
    val agentId: String,
    val orbStyle: Int,
    val tooltip: String,
)

private fun AgentRailOrbParams.toFlags(): AgentRailOrbFlags {
    val group = group
    return AgentRailOrbFlags(
        selected = focus.selectedAgentId != null && focus.selectedAgentId in group.agentIds,
        thinking = focus.thinkingAgentId != null && focus.thinkingAgentId in group.agentIds,
        count = group.agentIds.size,
    )
}

private fun AgentRailOrbParams.toTarget(flags: AgentRailOrbFlags): AgentRailOrbTarget {
    val group = group
    // Clicking a stack opens its already-selected member if one is
    // selected, otherwise its first (most-recent) member.
    val targetAgentId = group.agentIds.firstOrNull { it == focus.selectedAgentId } ?: group.agentIds.first()
    // Use the stack member's saved avatar style if any set one,
    // otherwise the position-derived colour.
    val orbStyle = group.agentIds.firstNotNullOfOrNull { focus.avatarStyleByAgentId[it] } ?: index
    val tooltip = buildString {
        append(group.name)
        if (flags.count > 1) append(" · ${flags.count} agents")
        if (flags.thinking) append(" · thinking…")
    }
    return AgentRailOrbTarget(agentId = targetAgentId, orbStyle = orbStyle, tooltip = tooltip)
}

@Composable
private fun AgentRailOrb(params: AgentRailOrbParams) {
    val flags = params.toFlags()
    val target = params.toTarget(flags)
    DesktopTooltip(text = target.tooltip) {
        AgentRailOrbContent(
            params = params,
            flags = flags,
            target = target,
        )
    }
}

@Composable
private fun AgentRailOrbContent(
    params: AgentRailOrbParams,
    flags: AgentRailOrbFlags,
    target: AgentRailOrbTarget,
) {
    Box(
        modifier = Modifier.size(width = 46.dp, height = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (flags.selected) {
            SelectedAgentRailMarker(modifier = Modifier.align(Alignment.CenterStart))
        }
        if (flags.thinking) {
            // Concentric with the 36dp/7dp orb (2dp gap → 9dp corner)
            // and sized to fit the 40dp slot so it doesn't crowd
            // neighbouring orbs.
            ThinkingRing(diameter = 40.dp, cornerRadius = 9.dp)
        }
        AgentOrb(
            index = target.orbStyle,
            size = 36.dp,
            onClick = { params.onAgentSelected(target.agentId) },
        ) {
            Text(
                text = params.group.name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
        // No member-count chip on stacked orbs: PM groups aggregate hundreds
        // of spawns, so every orb wore a meaningless "99+". The tooltip still
        // reports the exact count for anyone who cares.
    }
}

@Composable
private fun SelectedAgentRailMarker(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 3.dp, height = 28.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
    )
}


private data class RailActionIconModel(
    val icon: ImageVector,
    val description: String,
    val onClick: () -> Unit,
    val tint: Color = Color.Unspecified,
)

@Composable
private fun RailActionIcon(model: RailActionIconModel) {
    DesktopTooltip(text = model.description) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable(onClick = model.onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = model.icon,
                contentDescription = model.description,
                tint = model.tint.takeIf { it != Color.Unspecified }
                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
