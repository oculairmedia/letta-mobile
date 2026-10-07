package com.letta.mobile.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.agents.AgentRailGroup
import com.letta.mobile.data.agents.AgentRailSpace
import com.letta.mobile.data.agents.deriveAgentSpaces
import com.letta.mobile.data.search.TextMatch
import com.letta.mobile.ui.shell.LocalShellChromeDecorations
import com.letta.mobile.ui.shell.ShellRowMenus
import com.letta.mobile.ui.shell.rail.ShellAgentRail
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.rail.ShellAgentRailState
import com.letta.mobile.ui.shell.rail.ShellRailActivity
import com.letta.mobile.ui.shell.rail.ShellRailAgentTile
import com.letta.mobile.ui.shell.rail.ShellRailEntry
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.rail.ShellRailMapping
import com.letta.mobile.ui.shell.rail.ShellThinkingRing
import com.letta.mobile.ui.shell.rail.railScrollFades
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.jewel.ui.component.TextField as JewelTextField

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

/** "8:07 PM" for today, else the relative label ("3d"). */
internal fun hoverTimeLabel(raw: String): String {
    val instant = runCatching { java.time.Instant.parse(raw) }.getOrNull() ?: return raw
    val zone = java.time.ZoneId.systemDefault()
    val local = instant.atZone(zone)
    return if (local.toLocalDate() == java.time.LocalDate.now(zone)) {
        local.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
    } else {
        formatRelativeTimestamp(raw)
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

/** The rail's focus (selected / thinking agent, looks, activity): the shared rail's. */
internal typealias DesktopAgentRailFocus = ShellRailFocus

/** What the orb hover shows: when the agent last spoke and what it said. */
internal typealias RailAgentActivity = ShellRailActivity

@Immutable
internal data class DesktopAgentRailState(
    val agents: List<Pair<String, String>>,
    val focus: DesktopAgentRailFocus,
    /** Spotify-style expanded library mode: names + spaces, not just orbs. */
    val expanded: Boolean = false,
    /** The fleet Home page is showing; its rail icon draws selected. */
    val homeSelected: Boolean = false,
    /** Agents the shared recents cut left off the rail (the "+N" control). */
    val hiddenAgentCount: Int = 0,
)

@Immutable
internal data class DesktopAgentRailActions(
    val onAgentSelected: (String) -> Unit,
    val onNewSession: () -> Unit,
    val onToggleExpanded: () -> Unit = {},
    /** Opens the fleet Home page. Home lives here in the rail, not in the per-agent sidebar. */
    val onHome: () -> Unit = {},
    /** An orb's right-click "Agent settings": edits that agent. */
    val onAgentSettings: ((String) -> Unit)? = null,
    /** The rail's "+N": the full agent directory. */
    val onShowAllAgents: (() -> Unit)? = null,
)

/**
 * Far-left workspace/agent rail (Penpot "App Mockups v2", 56.dp wide, #0A0A0A): the shared
 * [ShellAgentRail] (letta-mobile-c3np7.2.11) - Home, the agent orbs, New - with the desktop's hover
 * cards and, when expanded, the desktop's names-and-spaces library in place of the orbs.
 */
@Composable
internal fun DesktopAgentRail(
    state: DesktopAgentRailState,
    actions: DesktopAgentRailActions,
) {
    val groups = remember(state.agents, state.focus.selectedAgentId) {
        ShellRailMapping.groups(state.agents, state.focus.selectedAgentId)
    }
    val entries = remember(groups, state.focus) { ShellRailMapping.entries(groups, state.focus) }
    // The desktop keeps no agent pins, so the orb menu (right-click) offers Open and Agent settings.
    val railActions = ShellAgentRailActions(
        onAgentSelected = actions.onAgentSelected,
        onHome = actions.onHome,
        onNewSession = actions.onNewSession,
        onAgentSettings = actions.onAgentSettings,
        onShowAllAgents = actions.onShowAllAgents,
    )
    CompositionLocalProvider(LocalShellChromeDecorations provides DesktopShellChromeDecorations) {
        ShellAgentRail(
            state = ShellAgentRailState(
                entries = entries,
                homeSelected = state.homeSelected,
                expanded = state.expanded,
                hiddenAgentCount = state.hiddenAgentCount,
            ),
            actions = railActions,
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
            library = { ExpandedAgentLibrary(groups = groups, entries = entries, actions = railActions) },
        )
    }
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
    entries: List<ShellRailEntry>,
    actions: ShellAgentRailActions,
) {
    // Spotify-style in-panel filter: search never leaves the library.
    var query by remember { mutableStateOf(TextFieldValue("")) }
    // The shared TextMatch, so the rail matches the way the command palette and every catalog do
    // ("pm letta mobile" finds "PM - letta-mobile").
    val filtered = remember(groups, query.text) {
        val needle = query.text.trim()
        if (needle.isEmpty()) groups else groups.filter { TextMatch.matches(needle, it.name) }
    }
    val spaces = remember(filtered) { deriveAgentSpaces(filtered) }
    // Entries carry the UNfiltered position's colour, so identities stay stable while filtering.
    val entryByName = remember(entries) { entries.associateBy { it.key } }
    LibrarySearchField(query = query, onQueryChange = { query = it })
    // Lazy: a large roster (hundreds of agents) must not compose every row.
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .railScrollFades(listState),
    ) {
        if (filtered.isEmpty()) {
            item(key = "library-empty") {
                Text(
                    text = "No agents match \"${query.text.trim()}\"",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.md),
                )
            }
        }
        spaces.forEach { space ->
            val spaceEntries = space.groups.mapNotNull { entryByName[it.name] }
            item(key = "space-${space.name}") {
                SpaceHeader(space = space, working = spaceEntries.any { it.thinking })
            }
            // Keyed by group name so per-row state (the thinking ring) follows its agent across recency reordering.
            items(spaceEntries, key = { "group-${it.key}" }) { entry ->
                // The same agent menu as the collapsed orbs (right-click).
                LocalShellChromeDecorations.current.rowMenu(ShellRowMenus.agent(entry, actions)) {
                    ExpandedAgentRow(entry = entry, onAgentSelected = actions.onAgentSelected)
                }
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
            .padding(horizontal = LettaDimens.Space.md)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(LettaDimens.Radius.sm))
            .padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(LettaDimens.Control.icon),
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
private fun SpaceHeader(space: AgentRailSpace, working: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = LettaDimens.Space.lg, end = LettaDimens.Space.lg, top = LettaDimens.Space.md, bottom = LettaDimens.Space.hair),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
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
                    .size(LettaDimens.Space.sm)
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
private fun ExpandedAgentRow(entry: ShellRailEntry, onAgentSelected: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = { onAgentSelected(entry.agentId) })
            .background(
                if (entry.selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            )
            .padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        Box(contentAlignment = Alignment.Center) {
            // A live mascot shows thinking itself; the ring is for the gradient orb only.
            if (entry.thinking && entry.identity == null) {
                ShellThinkingRing(diameter = LettaDimens.Orb.md)
            }
            ShellRailAgentTile(entry = entry, size = LettaDimens.Orb.lg, cornerRadius = LettaDimens.Radius.md)
        }
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (entry.selected) FontWeight.SemiBold else FontWeight.Normal,
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

/**
 * Light-dismiss for the expanded agent library: any press to the right of the rail collapses
 * it. Observed without consuming, so the press still lands on whatever was clicked.
 */
internal fun Modifier.railLightDismiss(expanded: Boolean, onDismiss: () -> Unit): Modifier =
    pointerInput(expanded) {
        if (!expanded) return@pointerInput
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                if (event.type != PointerEventType.Release) continue
                val x = event.changes.firstOrNull()?.position?.x ?: continue
                if (x > LettaDimens.Pane.railExpandedWidth.toPx()) onDismiss()
            }
        }
    }
