package com.letta.mobile.ui.shell.palette

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.letta.mobile.ui.search.LettaSearchRow
import com.letta.mobile.ui.search.LettaSearchRowLeading
import com.letta.mobile.ui.search.LettaSearchSection
import com.letta.mobile.ui.shell.header.UnifiedSearchHit
import com.letta.mobile.ui.shell.header.UnifiedSearchKind
import com.letta.mobile.ui.shell.header.UnifiedSearchQuery
import com.letta.mobile.ui.shell.header.UnifiedSearchSources
import com.letta.mobile.ui.shell.header.UnifiedSearchSurface
import com.letta.mobile.ui.shell.header.UnifiedShellSearch
import com.letta.mobile.ui.shell.header.rememberUnifiedSearchSections

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
 * The palette's results: the unified search's ([UnifiedShellSearch], the header's model) for
 * [query] on the palette surface (conversations, message text, agents, destinations), as picker
 * sections. The palette never narrows to the focused agent: it is how you reach the others.
 */
@Composable
internal fun rememberPaletteSections(sources: UnifiedSearchSources, query: String, recent: PaletteRecent? = null): List<PickerSection<LettaSearchRow>> {
    val unified = rememberUnifiedSearchSections(UnifiedSearchQuery(query, filterToAgent = false, surface = UnifiedSearchSurface.Palette), sources)
    return remember(unified, recent, sources) { paletteSections(unified, recent, sources) }
}

/** [sections] as picker sections; with [recent] the first agents also head the list while nothing is typed. */
internal fun paletteSections(sections: List<LettaSearchSection>, recent: PaletteRecent?, sources: UnifiedSearchSources): List<PickerSection<LettaSearchRow>> {
    val groups = sections.map { section -> PickerSection(section.title, section.title, section.rows.map { it.toPickerItem(section.title) }) }
    recent ?: return groups
    val agents = UnifiedShellSearch.sections(UnifiedSearchQuery("", surface = UnifiedSearchSurface.Palette, scopeId = UnifiedSearchKind.Agents.id), sources)
        .flatMap { it.rows }
        .take(recent.limit)
    if (agents.isEmpty()) return groups
    return listOf(PickerSection.recent(recent.title, agents.map { it.toPickerItem("recent") })) + groups
}

/** A recent section of up to [limit] agents titled [title]. */
data class PaletteRecent(val title: String, val limit: Int)

private fun LettaSearchRow.toPickerItem(prefix: String): PickerItem<LettaSearchRow> =
    PickerItem(key = "$prefix-$id", value = this, title = label, trailing = sublabel ?: meta)

/** What a picked palette row opens. */
internal fun PickerItem<LettaSearchRow>.hit(): UnifiedSearchHit? = UnifiedShellSearch.hitOf(value)

/** A palette row's leading glyph: the unified search's own (the agent's avatar, or a destination's arrow). */
@Composable
internal fun PaletteRowLeading(row: LettaSearchRow) = LettaSearchRowLeading(row.leading, row.label)

/**
 * The Cmd/Ctrl-K command palette (letta-mobile-ckmub.3.6): [LettaPicker] in command mode over the
 * unified search (letta-mobile-ckmub.3.9: one index and ranking with the header's search), in a
 * modal pane (a dialog on a desktop pointer, a sheet on touch). The search takes the caret on open;
 * Enter runs the highlighted row, which goes to [onOpen].
 */
@Composable
fun CommandPalettePane(sources: UnifiedSearchSources, onOpen: (UnifiedSearchHit) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val sections = rememberPaletteSections(sources, query)
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
                picked.hit()?.let(onOpen)
                onDismiss()
            },
            onDismiss = onDismiss,
        ),
        pane = PickerPane(testTag = CommandPaletteTags.PANE),
        slots = PickerSlots(leading = { PaletteRowLeading(it.value) }),
    )
}

private const val COMMAND_PALETTE_PLACEHOLDER = "Search conversations, messages, agents and pages"
