package com.letta.mobile.ui.shell.palette

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.Lucide
import com.letta.mobile.data.search.CommandPalette
import com.letta.mobile.data.search.PaletteItem
import com.letta.mobile.data.search.PaletteItemKind
import com.letta.mobile.data.search.mascotAgentId
import com.letta.mobile.ui.chat.AgentOrb
import com.letta.mobile.ui.components.picker.LettaPicker
import com.letta.mobile.ui.components.picker.LettaPickerSpec
import com.letta.mobile.ui.components.picker.PickMode
import com.letta.mobile.ui.components.picker.PickerCallbacks
import com.letta.mobile.ui.components.picker.PickerItem
import com.letta.mobile.ui.components.picker.PickerPane
import com.letta.mobile.ui.components.picker.PickerSearch
import com.letta.mobile.ui.components.picker.PickerSection
import com.letta.mobile.ui.components.picker.PickerSlots
import com.letta.mobile.ui.components.picker.PickerTags
import com.letta.mobile.ui.theme.LettaDimens

/** Test tags of the command palette and the quick-query bar. */
object CommandPaletteTags {
    const val PANE = "command-palette"
    const val SEARCH = "command-palette-search"
    const val ROW_PREFIX = "command-palette-row-"
    const val QUICK_QUERY = "quick-query"
    const val AMBIENT = "quick-query-ambient"
}

internal val PaletteTags = PickerTags(search = CommandPaletteTags.SEARCH, rowPrefix = CommandPaletteTags.ROW_PREFIX)

/**
 * The palette's sections: the shared [CommandPalette] filters and orders the items; with
 * [recent] the first agents also head the list as a recent section while nothing is typed.
 */
fun paletteSections(items: List<PaletteItem>, query: String, recent: PaletteRecent? = null): List<PickerSection<PaletteItem>> {
    val groups = CommandPalette.grouped(items, query).map { (kind, results) ->
        PickerSection(kind.name, CommandPalette.sectionTitle(kind), results.map { it.toPickerItem(kind.name) })
    }
    recent ?: return groups
    val agents = items.filter { it.kind == PaletteItemKind.Agent }.take(recent.limit)
    if (agents.isEmpty()) return groups
    return listOf(PickerSection.recent(recent.title, agents.map { it.toPickerItem("recent") })) + groups
}

/** A recent section of up to [limit] agents titled [title]. */
data class PaletteRecent(val title: String, val limit: Int)

private fun PaletteItem.toPickerItem(prefix: String): PickerItem<PaletteItem> =
    PickerItem(key = "$prefix-$id", value = this, title = label, trailing = sublabel)

/** A palette row's leading glyph: an arrow for destinations, the owning agent's orb otherwise. */
@Composable
fun PaletteItemLeading(item: PaletteItem) {
    if (item.kind == PaletteItemKind.Destination) {
        Icon(
            imageVector = Lucide.ArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(LettaDimens.Control.icon),
        )
    } else {
        AgentOrb(index = item.orbIndex ?: 0, size = LettaDimens.Orb.sm, cornerRadius = LettaDimens.Radius.sm, agentId = item.mascotAgentId())
    }
}

/**
 * The Cmd/Ctrl-K command palette (letta-mobile-ckmub.3.6): [LettaPicker] in command mode over
 * conversations, agents and destinations, in a modal pane (a dialog on a desktop pointer, a sheet
 * on touch). The search takes the caret on open; Enter runs the highlighted row.
 */
@Composable
fun CommandPalettePane(items: List<PaletteItem>, onSelect: (PaletteItem) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val sections = remember(items, query) { paletteSections(items, query) }
    LettaPicker(
        spec = LettaPickerSpec(
            sections = sections,
            query = query,
            mode = PickMode.Command,
            filterLocally = false,
            search = PickerSearch(COMMAND_PALETTE_PLACEHOLDER),
            tags = PaletteTags,
        ),
        callbacks = PickerCallbacks(
            onQuery = { query = it },
            onPick = { picked ->
                onSelect(picked.value)
                onDismiss()
            },
            onDismiss = onDismiss,
        ),
        pane = PickerPane(testTag = CommandPaletteTags.PANE),
        slots = PickerSlots(leading = { PaletteItemLeading(it.value) }),
    )
}

private const val COMMAND_PALETTE_PLACEHOLDER = "Search conversations, agents and pages"
