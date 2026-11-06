package com.letta.mobile.ui.shell.rail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
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
    return remember(state, pins, refs, source) {
        val registry = pins.registry
        val facets = state.agents.associate { (id, _) ->
            id to (registry.describe(ObjectRef("agent", id), VerbContext("rail"))?.facets ?: ObjectFacets())
        }
        state.copy(focus = state.focus.copy(
            objectFacetsByAgentId = facets,
            pinnedAgentIds = facets.filterValues { it.pinned == true }.keys,
        ))
    }
}
