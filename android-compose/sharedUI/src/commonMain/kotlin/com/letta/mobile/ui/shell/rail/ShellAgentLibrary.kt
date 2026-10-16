package com.letta.mobile.ui.shell.rail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.letta.mobile.data.agents.AgentRailGroup
import com.letta.mobile.data.agents.deriveAgentSpaces
import com.letta.mobile.data.search.TextMatch
import com.letta.mobile.ui.components.LettaEmptyHint
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.components.list.RosterActions
import com.letta.mobile.ui.components.list.RosterList
import com.letta.mobile.ui.components.list.RosterListTag
import com.letta.mobile.ui.components.list.RosterListRow
import com.letta.mobile.ui.components.list.RosterOptions
import com.letta.mobile.ui.components.list.RosterRowSlots
import com.letta.mobile.ui.components.list.RosterSection
import com.letta.mobile.ui.components.list.RosterSections
import com.letta.mobile.ui.components.list.RosterSelection
import com.letta.mobile.ui.components.list.RosterSpec
import com.letta.mobile.ui.components.list.rememberRosterListState
import com.letta.mobile.ui.components.picker.LettaPickerSearchField
import com.letta.mobile.ui.components.picker.PickerSearch
import com.letta.mobile.ui.shell.ShellRowMenus
import com.letta.mobile.ui.theme.LettaDimens

/** Test tags of the expanded rail's agent library. */
object ShellAgentLibraryTags {
    const val LIST = "shell-agent-library"
    const val SEARCH = "shell-agent-library-search"
}

/** The rail with its expanded library: the agents (id, name) folded into stacks, then drawn. */
@Immutable
data class ShellLibraryRailState(
    val agents: List<Pair<String, String>>,
    val focus: ShellRailFocus,
    val expanded: Boolean = false,
    val homeSelected: Boolean = false,
)

/**
 * letta-mobile-ckmub.3.10 (hard cut S7): the agent rail a pointer host shows, collapsed to orbs or
 * expanded to [ShellAgentLibrary]. Each orb keeps its agent's mascot (or gradient orb).
 */
@Composable
fun ShellLibraryRail(state: ShellLibraryRailState, actions: ShellAgentRailActions, modifier: Modifier = Modifier) {
    val groups = remember(state.agents, state.focus.selectedAgentId) { ShellRailMapping.groups(state.agents, state.focus.selectedAgentId) }
    val entries = remember(groups, state.focus) { ShellRailMapping.entries(groups, state.focus) }
    ShellAgentRail(
        state = ShellAgentRailState(
            entries = entries,
            homeSelected = state.homeSelected,
            expanded = state.expanded,
        ),
        actions = actions,
        modifier = modifier.background(MaterialTheme.colorScheme.background),
        library = { ShellAgentLibrary(groups = groups, entries = entries, actions = actions) },
    )
}

/**
 * The expanded rail ("Your Library"): agents grouped into spaces derived from their names, each
 * space headed by its member count and a live dot while one of them works, pinned agents first,
 * filtered in place by the search field. A [RosterList], so the keyboard walks it and each row's
 * menu is the orb's (right-click on pointer, long-press on touch).
 */
@Composable
fun ColumnScope.ShellAgentLibrary(groups: List<AgentRailGroup>, entries: List<ShellRailEntry>, actions: ShellAgentRailActions) {
    var query by rememberSaveable { mutableStateOf("") }
    val needle = query.trim()
    // Entries carry the unfiltered position's colour, so identities stay stable while filtering.
    val sections = remember(groups, entries, needle) { librarySections(groups, entries, needle) }
    LettaPickerSearchField(
        query = query,
        onQueryChange = { query = it },
        search = PickerSearch(placeholder = "Search agents", autoFocus = false),
        fieldModifier = Modifier,
    )
    val roster = rememberRosterListState()
    RosterList(
        RosterSpec(
            sections = sections,
            key = ShellRailEntry::key,
            selection = RosterSelection(isSelected = ShellRailEntry::selected, onSelect = { actions.onAgentSelected(it.agentId) }),
            actions = RosterActions(menu = { entry, _ -> ShellRowMenus.agent(entry, actions) }),
            options = RosterOptions(testTag = RosterListTag(ShellAgentLibraryTags.LIST)),
            empty = { LettaEmptyHint("No agents match \"$needle\"", Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.md)) },
        ),
        modifier = Modifier.weight(1f).fillMaxWidth().railScrollFades(roster.lazy),
        state = roster,
    ) { entry, row ->
        RosterListRow(
            spec = LettaListRowSpec(title = entry.name, emphasized = entry.selected),
            row = row,
            slots = RosterRowSlots(leading = { LibraryAgentTile(entry) }),
        )
    }
}

/** The pinned agents, then one section per space; an empty filter result has no sections at all. */
internal fun librarySections(groups: List<AgentRailGroup>, entries: List<ShellRailEntry>, needle: String): List<RosterSection<ShellRailEntry>> {
    val filtered = if (needle.isEmpty()) groups else groups.filter { TextMatch.matches(needle, it.name) }
    val entryByName = entries.associateBy { it.key }
    val pinned = filtered.mapNotNull { entryByName[it.name] }.filter { it.pinned }
    val pinnedSection = RosterSection("pinned", pinned, title = RosterSections.PINNED_TITLE).takeIf { pinned.isNotEmpty() }
    val spaces = deriveAgentSpaces(filtered).map { space ->
        val members = space.groups.mapNotNull { entryByName[it.name] }.filterNot { it.pinned }
        RosterSection(
            key = "space-${space.name}",
            items = members,
            title = space.name,
            headerEnd = { SpaceHeaderEnd(count = space.agentCount, working = members.any { it.thinking }) },
        )
    }
    return listOfNotNull(pinnedSection) + spaces.filter { it.items.isNotEmpty() }
}

/** A space's aggregate: a live dot while one of its agents works, then its member count. */
@Composable
private fun SpaceHeaderEnd(count: Int, working: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        if (working) Box(Modifier.size(LettaDimens.Space.sm).background(MaterialTheme.colorScheme.primary, CircleShape))
        Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The agent's mascot (or gradient orb); a live mascot shows thinking itself, the orb wears the ring. */
@Composable
private fun LibraryAgentTile(entry: ShellRailEntry) {
    Box(contentAlignment = Alignment.Center) {
        if (entry.thinking && entry.identity == null) ShellThinkingRing(diameter = LettaDimens.Orb.md)
        ShellRailAgentTile(entry = entry, size = LettaDimens.Orb.lg, cornerRadius = LettaDimens.Radius.md)
    }
}

/**
 * Light-dismiss for the expanded library: any press to the right of the rail collapses it.
 * Observed without consuming, so the press still lands on whatever was clicked.
 */
fun Modifier.railLightDismiss(expanded: Boolean, onDismiss: () -> Unit): Modifier =
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
