package com.letta.mobile.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.Agent
import com.letta.mobile.desktop.chat.AgentOrb
import org.jetbrains.jewel.ui.component.TextField as JewelTextField

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
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val filtered = remember(directory, query.text) { filterAgentDirectory(directory, query.text) }
    val sections = remember(filtered) { groupAgentDirectory(filtered) }
    val searching = query.text.isNotBlank()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = actions.onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 520.dp, max = 640.dp)
                .heightIn(max = 640.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                // Hoisted here (not on the field) so Escape/Enter still work
                // after a click moves focus off the To: field.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
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
                },
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 8.dp,
        ) {
            Column {
                Text(
                    text = "New conversation",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "To:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    JewelTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Type an agent name") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                }
                DirectoryDivider()
                CreateAgentRow(onClick = actions.onCreateNewAgent)
                DirectoryDivider()
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    if (!searching && recents.isNotEmpty()) {
                        item(key = "recents") { RecentsRow(recents, actions.onAgentSelected) }
                    }
                    if (filtered.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                text = "No agents match \"${query.text}\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(20.dp),
                            )
                        }
                    }
                    sections.forEach { (letter, rows) ->
                        item(key = "letter-$letter") {
                            Text(
                                text = letter,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(rows, key = { "agent-${it.id}" }) { row ->
                            AgentDirectoryRow(row = row, onClick = { actions.onAgentSelected(row.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectoryDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun CreateAgentRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = "Create new agent",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun RecentsRow(
    recents: List<NewConversationAgentRow>,
    onAgentSelected: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "RECENT",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            recents.forEach { row ->
                Column(
                    modifier = Modifier
                        .clickable(onClick = { onAgentSelected(row.id) })
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                        .width(64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AgentOrb(index = row.orbIndex, size = 44.dp, cornerRadius = 12.dp)
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentDirectoryRow(row: NewConversationAgentRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AgentOrb(index = row.orbIndex, size = 34.dp, cornerRadius = 9.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
