package com.letta.mobile.ui.shell.palette

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Palette
import com.letta.mobile.data.model.Agent
import com.letta.mobile.ui.chat.AgentOrb
import com.letta.mobile.ui.components.LettaListRow
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.components.picker.LettaPicker
import com.letta.mobile.ui.components.picker.LettaPickerSpec
import com.letta.mobile.ui.components.picker.PickMode
import com.letta.mobile.ui.components.picker.PickerCallbacks
import com.letta.mobile.ui.components.picker.PickerEmpty
import com.letta.mobile.ui.components.picker.PickerItem
import com.letta.mobile.ui.components.picker.PickerPane
import com.letta.mobile.ui.components.picker.PickerSearch
import com.letta.mobile.ui.components.picker.PickerSection
import com.letta.mobile.ui.components.picker.PickerSlots
import com.letta.mobile.ui.theme.LettaDimens

/** One agent in the New conversation directory: its roster subtitle and the orb the rail shows for it. */
@Immutable
data class NewConversationAgent(
    val id: String,
    val name: String,
    val subtitle: String?,
    val orbIndex: Int,
)

/**
 * What the New conversation picker reports. The picker closes itself ([onDismiss]) before it runs a
 * pick or an action. A null [onNewCanvas] leaves the canvas action out.
 */
@Immutable
class NewConversationActions(
    val onAgentSelected: (String) -> Unit,
    val onCreateAgent: () -> Unit,
    val onDismiss: () -> Unit,
    val onNewCanvas: (() -> Unit)? = null,
)

/** The agents the picker lists: [directory] A to Z, the first [recentLimit] of it as Recent. */
@Immutable
data class NewConversationDirectory(val directory: List<NewConversationAgent>, val recentLimit: Int)

object NewConversationPickerTags {
    const val PANE = "new-conversation"
    const val CREATE_AGENT = "new-conversation-create-agent"
    const val NEW_CANVAS = "new-conversation-new-canvas"
}

/**
 * The directory's agents from the rail's recency-ordered (id, name) pairs, each with a roster
 * subtitle (description, else model) and the orb the rail shows (avatar override, else position).
 */
fun newConversationAgents(
    railAgents: List<Pair<String, String>>,
    rosterAgents: List<Agent>,
    avatarStyleByAgentId: Map<String, Int>,
): List<NewConversationAgent> {
    val rosterById = rosterAgents.associateBy { it.id.value }
    return railAgents.mapIndexed { index, (id, name) ->
        val roster = rosterById[id]
        NewConversationAgent(
            id = id,
            name = name,
            subtitle = roster?.description?.takeIf { it.isNotBlank() } ?: roster?.model?.takeIf { it.isNotBlank() },
            orbIndex = avatarStyleByAgentId[id] ?: index,
        )
    }
}

/**
 * The picker's sections: Recent (gone once the user types), then the agents under letter headers,
 * A to Z by name with everything that does not start with a letter under "#", last.
 */
fun newConversationSections(directory: NewConversationDirectory): List<PickerSection<NewConversationAgent>> {
    val recent = directory.directory.take(directory.recentLimit).map { it.toItem("recent") }
    val letters = directory.directory
        .sortedBy { it.name.lowercase() }
        .groupBy { it.directoryLetter() }
        .toList()
        .sortedWith(compareBy({ it.first == NON_LETTER }, { it.first }))
        .map { (letter, agents) -> PickerSection(letter, letter, agents.map { it.toItem("agent") }) }
    return if (recent.isEmpty()) letters else listOf(PickerSection.recent(RECENT_TITLE, recent)) + letters
}

private fun NewConversationAgent.directoryLetter(): String {
    val first = name.trim().firstOrNull()?.uppercaseChar()
    return if (first != null && first in 'A'..'Z') first.toString() else NON_LETTER
}

private fun NewConversationAgent.toItem(prefix: String): PickerItem<NewConversationAgent> =
    PickerItem(key = "$prefix-$id", value = this, title = name, subtitle = subtitle)

/**
 * letta-mobile-ckmub.3.8 (S8b): "New conversation", a contacts-style directory on the shared
 * [LettaPicker]: create-agent (and new-canvas) actions pinned under the search, Recent, then every
 * agent under letter headers, each with its live orb. Enter opens the best match; picking an agent
 * opens (or starts) its latest conversation through the caller.
 */
@Composable
fun NewConversationPicker(directory: NewConversationDirectory, actions: NewConversationActions) {
    var query by remember { mutableStateOf("") }
    val sections = remember(directory) { newConversationSections(directory) }
    LettaPicker(
        spec = LettaPickerSpec(
            sections = sections,
            query = query,
            mode = PickMode.Command,
            search = PickerSearch(SEARCH_PLACEHOLDER),
            empty = PickerEmpty("No agents match \"${query.trim()}\""),
        ),
        callbacks = PickerCallbacks(
            onQuery = { query = it },
            onPick = { picked -> actions.closeThen { onAgentSelected(picked.value.id) } },
            onDismiss = actions.onDismiss,
        ),
        pane = PickerPane(title = TITLE, testTag = NewConversationPickerTags.PANE),
        slots = PickerSlots(
            leading = { AgentOrb(index = it.value.orbIndex, size = LettaDimens.Orb.sm, agentId = it.value.id) },
            header = {
                ActionRow(CreateAgentAction) { actions.closeThen { onCreateAgent() } }
                actions.onNewCanvas?.let { newCanvas -> ActionRow(NewCanvasAction) { actions.closeThen { newCanvas() } } }
            },
        ),
    )
}

private inline fun NewConversationActions.closeThen(run: NewConversationActions.() -> Unit) {
    onDismiss()
    run()
}

/** A pinned action row's label, icon and test tag. */
@Immutable
private class PinnedAction(val label: String, val icon: ImageVector, val tag: String)

private val CreateAgentAction = PinnedAction("Create new agent", Icons.Outlined.Add, NewConversationPickerTags.CREATE_AGENT)
private val NewCanvasAction = PinnedAction("New canvas", Lucide.Palette, NewConversationPickerTags.NEW_CANVAS)

@Composable
private fun ActionRow(action: PinnedAction, onClick: () -> Unit) {
    LettaListRow(
        spec = LettaListRowSpec(action.label, action.icon),
        onClick = onClick,
        modifier = Modifier.padding(horizontal = LettaDimens.Space.sm).testTag(action.tag),
    )
}

private const val TITLE = "New conversation"
private const val SEARCH_PLACEHOLDER = "Search agents"
private const val RECENT_TITLE = "Recent"
private const val NON_LETTER = "#"
