package com.letta.mobile.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import com.letta.mobile.ui.components.foundation.LettaChip
import com.letta.mobile.ui.components.foundation.LettaChipSlots
import com.letta.mobile.ui.components.foundation.LettaDivider
import com.letta.mobile.ui.components.foundation.LettaIcon
import com.letta.mobile.ui.components.foundation.LettaIconStyle
import com.letta.mobile.ui.components.foundation.LettaInlineSearchField
import com.letta.mobile.ui.components.foundation.LettaPanel
import com.letta.mobile.ui.components.foundation.LettaSearchOptions
import com.letta.mobile.ui.components.row.LettaListRow
import com.letta.mobile.ui.components.row.LettaListRowSpec
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import com.letta.mobile.ui.theme.tokens.LettaSurfaceSizes

/**
 * "Choose agents" as a compact search picker instead of one checkbox per agent: the chosen agents
 * as removable chips (so a filter or the scroll never hides one), then a panel with a search field
 * that filters as you type over a short scrolling list of matches, each row a checkbox.
 * Presentation only: [selected] and [onChange] are the scope form's own set of agent ids.
 */
@Composable
internal fun AgentScopePicker(agents: List<AgentChoice>, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val toggle: (String) -> Unit = { id -> onChange(if (id in selected) selected - id else selected + id) }
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm), modifier = Modifier.fillMaxWidth()) {
        val chosen = agents.filter { it.id in selected }
        if (chosen.isNotEmpty()) ChosenAgentChips(chosen, onRemove = toggle)
        LettaPanel(modifier = Modifier.fillMaxWidth()) {
            Column {
                LettaInlineSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    options = LettaSearchOptions(
                        placeholder = PairingStrings.INVITE_SEARCH_AGENTS,
                        fieldModifier = Modifier.testTag(PairingTags.INVITE_AGENT_SEARCH),
                    ),
                )
                LettaDivider()
                AgentMatches(agents.filter { agentMatches(query, it.name) }, selected, query, onToggle = toggle)
            }
        }
    }
}

/** Every token typed appears in the agent's name, ignoring case; a blank query matches every agent. */
internal fun agentMatches(query: String, name: String): Boolean =
    query.split(' ').filter { it.isNotBlank() }.all { name.contains(it, ignoreCase = true) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChosenAgentChips(chosen: List<AgentChoice>, onRemove: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        modifier = Modifier.fillMaxWidth().testTag(PairingTags.INVITE_CHOSEN_AGENTS),
    ) {
        chosen.forEach { agent ->
            LettaChip(
                text = agent.name,
                onClick = { onRemove(agent.id) },
                modifier = Modifier
                    .semantics { contentDescription = PairingStrings.removeAgent(agent.name) }
                    .testTag(PairingTags.chosenAgent(agent.id)),
                slots = REMOVE_SLOT,
            )
        }
    }
}

/** The matches, at most [LettaSurfaceSizes.agentPickerListMaxHeight] tall before the list scrolls. */
@Composable
private fun AgentMatches(matches: List<AgentChoice>, selected: Set<String>, query: String, onToggle: (String) -> Unit) {
    if (matches.isEmpty()) {
        Text(
            PairingStrings.noAgentMatch(query.trim()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(LettaDimens.Space.lg).testTag(PairingTags.INVITE_AGENT_NO_MATCH),
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = LettaSurfaceSizes.agentPickerListMaxHeight)
            .testTag(PairingTags.INVITE_AGENT_LIST),
    ) {
        items(matches, key = { it.id }) { agent -> AgentRow(agent, checked = agent.id in selected) { onToggle(agent.id) } }
    }
}

/** One match: the shared list row, the whole row toggling its checkbox (one accessible checkbox node). */
@Composable
private fun AgentRow(agent: AgentChoice, checked: Boolean, onToggle: () -> Unit) {
    LettaListRow(
        spec = LettaListRowSpec(agent.name, role = Role.Checkbox),
        onClick = onToggle,
        modifier = Modifier
            .semantics { toggleableState = ToggleableState(checked) }
            .testTag(PairingTags.agentOption(agent.id)),
        end = { Checkbox(checked = checked, onCheckedChange = null) },
    )
}

/** A chosen agent's chip ends in the shared close glyph: tapping the chip removes the agent. */
private val REMOVE_SLOT = LettaChipSlots(
    trailing = { LettaIcon(LettaIcons.Close, contentDescription = null, style = LettaIconStyle(LettaControlTokens.iconSm)) },
)
