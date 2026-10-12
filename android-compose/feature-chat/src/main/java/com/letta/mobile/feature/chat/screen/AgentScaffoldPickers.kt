package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.feature.chat.R
import com.letta.mobile.ui.components.picker.LettaPicker
import com.letta.mobile.ui.components.picker.LettaPickerSpec
import com.letta.mobile.ui.components.picker.PickerCallbacks
import com.letta.mobile.ui.components.picker.PickerEmpty
import com.letta.mobile.ui.components.picker.PickerItem
import com.letta.mobile.ui.components.picker.PickerPane
import com.letta.mobile.ui.components.picker.PickerSearch
import com.letta.mobile.ui.components.picker.PickerSection
import com.letta.mobile.ui.components.picker.PickerTags
import com.letta.mobile.ui.haptics.HapticEffects
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject

/**
 * The header pill's agent switcher (hard cut S9): the shared [LettaPicker] (a sheet on a phone)
 * over the agent roster, favourite and pinned first ([sortAgentsForPicker]). The search matches the
 * name, id, description, model and tags. Pinning lives on the drawer rail's orb menu and the pill's
 * long-press.
 */
@Composable
internal fun AgentPickerSheet(
    agents: List<Agent>,
    currentAgentId: String,
    favoriteAgentId: String?,
    pinnedAgentIds: Set<String>,
    onDismiss: () -> Unit,
    onAgentSelected: (Agent) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val defaultName = stringResource(R.string.screen_drawer_default_agent_name)
    val favoriteLabel = stringResource(R.string.screen_agents_favorite_indicator)
    val pinnedLabel = stringResource(R.string.screen_agents_pinned_indicator)
    val items = remember(agents, favoriteAgentId, pinnedAgentIds, defaultName, favoriteLabel, pinnedLabel) {
        agents.map { agent ->
            PickerItem(
                key = agent.id.value,
                value = agent,
                title = agent.name.ifBlank { defaultName },
                subtitle = agent.model ?: agent.description?.takeIf { it.isNotBlank() } ?: agent.id.value.take(12),
                trailing = listOfNotNull(
                    favoriteLabel.takeIf { agent.id.value == favoriteAgentId },
                    pinnedLabel.takeIf { agent.id.value in pinnedAgentIds },
                ).joinToString(" - ").ifBlank { null },
                keywords = listOfNotNull(agent.id.value, agent.description, agent.model, agent.tags.joinToString(" "))
                    .joinToString(" "),
            )
        }
    }
    LettaPicker(
        spec = LettaPickerSpec(
            sections = listOf(PickerSection(key = "agents", title = null, items = items)),
            query = query,
            selected = setOf(currentAgentId),
            search = PickerSearch(stringResource(R.string.screen_agents_search_hint), autoFocus = false),
            empty = PickerEmpty(
                if (query.isBlank()) {
                    stringResource(R.string.screen_agents_empty)
                } else {
                    stringResource(R.string.screen_agents_no_matches, query)
                },
            ),
            tags = PickerTags(search = AgentScaffoldTestTags.AGENT_PICKER_SEARCH_FIELD),
        ),
        callbacks = PickerCallbacks(
            onQuery = { query = it },
            onPick = { item ->
                HapticEffects.segmentTick(haptic, view, enabled = item.key != currentAgentId)
                onAgentSelected(item.value)
            },
            onDismiss = onDismiss,
        ),
        pane = PickerPane(title = stringResource(R.string.common_agents), testTag = AgentScaffoldTestTags.AGENT_PICKER_SHEET),
    )
}

internal fun sortAgentsForPicker(
    agents: List<Agent>,
    favoriteAgentId: String?,
    pinnedAgentIds: Set<String>,
): List<Agent> = agents
    .distinctBy { it.id.value }
    .sortedWith(
        compareByDescending<Agent> { it.id.value == favoriteAgentId }
            .thenByDescending { it.id.value in pinnedAgentIds }
            .thenBy { it.name.lowercase(Locale.getDefault()) },
    )

/** The drawer's conversation repository, when the scaffold's caller does not hand one in. */
@HiltViewModel
internal class ConversationPickerViewModel @Inject constructor(
    val conversationRepository: IConversationRepository,
) : ViewModel()
