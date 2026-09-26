package com.letta.mobile.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.letta.mobile.data.model.Agent
import com.letta.mobile.ui.search.LettaSearchAction
import com.letta.mobile.ui.search.LettaSearchConfig
import com.letta.mobile.ui.search.LettaSearchLeading
import com.letta.mobile.ui.search.LettaSearchPopover
import com.letta.mobile.ui.search.LettaSearchRow
import com.letta.mobile.ui.search.LettaSearchSection
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Palette

/** One agent entry in the New Conversation directory. */
internal data class NewConversationAgentRow(
    val id: String,
    val name: String,
    val subtitle: String?,
    val orbIndex: Int,
)

internal data class DesktopNewConversationActions(
    val onAgentSelected: (String) -> Unit,
    val onCreateNewAgent: () -> Unit,
    val onDismiss: () -> Unit,
    /** Grok-style action row under "Create new agent"; null hides it. */
    val onNewCanvas: (() -> Unit)? = null,
)

/**
 * Builds the directory rows from the rail's recency-ordered (id, name) pairs,
 * enriching each with a roster subtitle (description, else model) and the
 * same orb index the rail shows (avatar override, else rail position).
 */
internal fun buildNewConversationRows(
    railAgents: List<Pair<String, String>>,
    rosterAgents: List<Agent>,
    avatarStyleByAgentId: Map<String, Int>,
): List<NewConversationAgentRow> {
    val rosterById = rosterAgents.associateBy { it.id.value }
    return railAgents.mapIndexed { index, (id, name) ->
        val roster = rosterById[id]
        NewConversationAgentRow(
            id = id,
            name = name,
            subtitle = roster?.description?.takeIf { it.isNotBlank() }
                ?: roster?.model?.takeIf { it.isNotBlank() },
            orbIndex = avatarStyleByAgentId[id] ?: index,
        )
    }
}

/** Case-insensitive name filter; blank query keeps everything. */
internal fun filterAgentDirectory(
    rows: List<NewConversationAgentRow>,
    query: String,
): List<NewConversationAgentRow> {
    val needle = query.trim()
    if (needle.isEmpty()) return rows
    return rows.filter { it.name.contains(needle, ignoreCase = true) }
}

/**
 * Groups rows into alphabetical sections (Google Messages-style letter
 * headers): A-Z by first letter, everything else under "#", sorted by name
 * within each section.
 */
internal fun groupAgentDirectory(
    rows: List<NewConversationAgentRow>,
): List<Pair<String, List<NewConversationAgentRow>>> =
    rows
        .sortedBy { it.name.lowercase() }
        .groupBy { row ->
            val first = row.name.trim().firstOrNull()?.uppercaseChar()
            if (first != null && first in 'A'..'Z') first.toString() else "#"
        }
        .toList()
        .sortedWith(compareBy({ it.first == "#" }, { it.first }))

/**
 * Contacts-style "New conversation" surface: a To: typeahead over the full
 * persistent-agent roster, a pinned create-agent action, a Recents row, and
 * an A-Z directory with letter headers. Selecting an agent opens (or creates)
 * its most recent conversation via the caller's openAgent path.
 */
@Composable
internal fun DesktopNewConversationSurface(
    recents: List<NewConversationAgentRow>,
    directory: List<NewConversationAgentRow>,
    actions: DesktopNewConversationActions,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(directory, query) { filterAgentDirectory(directory, query) }
    val sections = remember(filtered) { groupAgentDirectory(filtered) }

    // Same LettaSearch module as the header, expressed as a popover with a
    // title, a "To:" prefix, pinned create actions and a recents strip. The
    // appearance this replaces is reproduced by flags, not by a second
    // implementation: one field, one row layout, one empty state.
    LettaSearchPopover(
        query = query,
        onQueryChange = { query = it },
        sections = sections.map { (letter, rows) ->
            LettaSearchSection(title = letter, rows = rows.map(NewConversationAgentRow::toSearchRow))
        },
        onRowSelected = { row -> actions.onAgentSelected(row.id) },
        onDismiss = actions.onDismiss,
        modifier = Modifier.onPreviewKeyEvent { event ->
            handleDirectoryKey(event, filtered, actions)
        },
        config = LettaSearchConfig(
            title = "New conversation",
            fieldPrefix = "To:",
            placeholder = "Search or create an agent",
            recents = recents.map(NewConversationAgentRow::toSearchRow),
            recentsTitle = "Recent",
            actions = buildList {
                add(
                    LettaSearchAction(
                        id = "create-agent",
                        labelForQuery = { "Create new agent" },
                        icon = Icons.Outlined.Add,
                        onInvoke = { actions.onCreateNewAgent() },
                    ),
                )
                actions.onNewCanvas?.let { onNewCanvas ->
                    add(
                        LettaSearchAction(
                            id = "new-canvas",
                            labelForQuery = { "New canvas" },
                            icon = Lucide.Palette,
                            onInvoke = { onNewCanvas() },
                        ),
                    )
                }
            },
            emptyText = { "No agents match \"$it\"" },
        ),
    )
}

/** The directory row as a search row: the orb carries the agent id, so it draws the live mascot. */
private fun NewConversationAgentRow.toSearchRow(): LettaSearchRow = LettaSearchRow(
    id = id,
    label = name,
    sublabel = subtitle,
    leading = LettaSearchLeading.Orb(orbIndex = orbIndex, agentId = id),
)

/** Escape dismisses; Enter opens the top filtered match. */
private fun handleDirectoryKey(
    event: androidx.compose.ui.input.key.KeyEvent,
    filtered: List<NewConversationAgentRow>,
    actions: DesktopNewConversationActions,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.Escape -> {
            actions.onDismiss()
            true
        }
        Key.Enter -> {
            filtered.firstOrNull()?.let { actions.onAgentSelected(it.id) }
            filtered.isNotEmpty()
        }
        else -> false
    }
}
