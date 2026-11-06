package com.letta.mobile.ui.shell.rail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.letta.mobile.data.model.DisplayNames
import com.letta.mobile.data.objectverbs.ObjectFacets
import com.letta.mobile.data.objectverbs.ObjectRef
import com.letta.mobile.data.objectverbs.VerbContext
import com.letta.mobile.data.objectverbs.binding.PinBinding

/** The shared rail's grouping/badges read the provider; hosts supply only a session binding. */
@Composable
fun rememberPinnedRailState(state: ShellLibraryRailState, pins: PinBinding?): ShellLibraryRailState {
    if (pins == null) return state
    val refs = pins.references.collectAsState().value
    val source = pins.source.collectAsState().value
    val available = pins.store.availability.collectAsState().value
    return remember(state, pins, refs, source, available) {
        pinnedRailState(state, pins, refs)
    }
}

/** Keep scoped pins visible even when the loaded roster does not include their names yet. */
internal fun pinnedRailState(state: ShellLibraryRailState, pins: PinBinding, refs: List<ObjectRef>): ShellLibraryRailState {
    val registry = pins.registry
    val ids = state.agents.map { it.first } + refs.filter { it.kind == "agent" }.map { it.id }
    val facets = ids.distinct().associateWith { id ->
        registry.describe(ObjectRef("agent", id), VerbContext("rail"))?.facets ?: ObjectFacets()
    }
    val rosterIds = state.agents.mapTo(mutableSetOf()) { it.first }
    val missing = refs.filter { it.kind == "agent" && it.id !in rosterIds && facets[it.id]?.pinned == true }
        .distinctBy { it.id }
        .map { it.id to DisplayNames.agent(null, it.id) }
    return state.copy(
        agents = if (missing.isEmpty()) state.agents else DisplayNames.disambiguateAgentFallbacks(state.agents + missing),
        focus = state.focus.copy(
            objectFacetsByAgentId = facets,
            pinnedAgentIds = facets.filterValues { it.pinned == true }.keys,
        ),
    )
}
