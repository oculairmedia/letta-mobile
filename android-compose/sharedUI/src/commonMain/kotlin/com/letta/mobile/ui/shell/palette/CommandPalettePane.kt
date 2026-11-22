package com.letta.mobile.ui.shell.palette

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.ui.components.foundation.LettaDivider
import com.letta.mobile.ui.components.foundation.LettaKbd
import com.letta.mobile.ui.components.foundation.LettaSpinner
import com.letta.mobile.ui.components.picker.PickerEmpty
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.shell.header.rememberUnifiedSearch
import com.letta.mobile.ui.theme.LettaDimens
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
import com.letta.mobile.data.search.MessageSearchSelection
import com.letta.mobile.ui.search.LettaSearchChipRows
import com.letta.mobile.ui.search.LettaSearchRow
import com.letta.mobile.ui.search.LettaSearchRowLeading
import com.letta.mobile.ui.search.LettaSearchSection
import com.letta.mobile.ui.search.messageSearchChipRows
import com.letta.mobile.ui.shell.header.UnifiedSearchHit
import com.letta.mobile.ui.shell.header.UnifiedSearchKind
import com.letta.mobile.ui.shell.header.UnifiedSearchQuery
import com.letta.mobile.ui.shell.header.UnifiedSearchSources
import com.letta.mobile.ui.shell.header.UnifiedSearchSurface
import com.letta.mobile.ui.shell.header.UnifiedShellSearch

/** Test tags of the command palette and the quick-query bar. */
object CommandPaletteTags {
    const val PANE = "command-palette"
    const val SEARCH = "command-palette-search"
    const val ROW_PREFIX = "command-palette-row-"
    const val QUICK_QUERY = "quick-query"
    const val AMBIENT = "quick-query-ambient"
    const val SEARCHING = "command-palette-searching"
    const val KEYS = "command-palette-keys"
}

internal val PaletteTags = PickerTags(search = CommandPaletteTags.SEARCH, rowPrefix = CommandPaletteTags.ROW_PREFIX)

/**
 * The palette's results: the unified search's ([UnifiedShellSearch], the header's model) for
 * [query] on the palette surface (conversations, message text, agents, destinations), as picker
 * sections. The palette never narrows to the focused agent: it is how you reach the others.
 */
@Composable
internal fun rememberPaletteSections(
    sources: UnifiedSearchSources,
    query: String,
    recent: PaletteRecent? = null,
    message: MessageSearchSelection = MessageSearchSelection(),
): List<PickerSection<LettaSearchRow>> = rememberPaletteResults(sources, query, recent, message).sections

/** The palette's [sections], and whether message text is still being searched for the query. */
@Immutable
internal class PaletteResults(val sections: List<PickerSection<LettaSearchRow>>, val searchingMessages: Boolean)

/** [rememberPaletteSections], with whether the message search has yet to answer. */
@Composable
internal fun rememberPaletteResults(
    sources: UnifiedSearchSources,
    query: String,
    recent: PaletteRecent? = null,
    message: MessageSearchSelection = MessageSearchSelection(),
): PaletteResults {
    val asked = UnifiedSearchQuery(query, filterToAgent = false, surface = UnifiedSearchSurface.Palette, message = message)
    val unified = rememberUnifiedSearch(asked, sources)
    val sections = remember(unified.sections, recent, sources) { paletteSections(unified.sections, recent, sources) }
    return remember(sections, unified.searchingMessages) { PaletteResults(sections, unified.searchingMessages) }
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
 * The Cmd/Ctrl-K command palette: [LettaPicker] in command mode over the
 * unified search (one index and ranking with the header's search), in a
 * modal pane (a dialog on a desktop pointer, a sheet on touch). The search takes the caret on open;
 * Enter runs the highlighted row, which goes to [onOpen].
 *
 * While message text is still being searched it says so under the search (the other results show
 * meanwhile), and an empty list says what was searched for and what can be. On a pointer host its
 * foot names the keys it answers to.
 */
@Composable
fun CommandPalettePane(sources: UnifiedSearchSources, onOpen: (UnifiedSearchHit) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf(MessageSearchSelection()) }
    val results = rememberPaletteResults(sources, query, message = message)
    val chips = messageSearchChipRows(sources.messageCapabilities, message) { message = it }
    val pointer = LocalLettaHost.current.input == LettaInput.Pointer
    LettaPicker(
        spec = LettaPickerSpec(
            sections = results.sections,
            query = query,
            mode = PickMode.Command,
            filterLocally = false,
            search = PickerSearch(COMMAND_PALETTE_PLACEHOLDER),
            empty = paletteEmpty(query, results.searchingMessages),
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
        slots = PickerSlots(
            leading = { PaletteRowLeading(it.value) },
            header = {
                LettaSearchChipRows(chips)
                if (results.searchingMessages) PaletteSearching()
            },
            footer = if (pointer) ({ PaletteKeys() }) else null,
        ),
    )
}

/** What an empty palette says: what to type, that messages are still being searched, or that nothing matched. */
internal fun paletteEmpty(query: String, searchingMessages: Boolean): PickerEmpty = when {
    query.isBlank() -> PickerEmpty(PALETTE_EMPTY_START, PALETTE_EMPTY_HINT)
    searchingMessages -> PickerEmpty(PALETTE_SEARCHING)
    else -> PickerEmpty(paletteNoMatch(query), PALETTE_EMPTY_HINT)
}

private fun paletteNoMatch(query: String): String = "No matches for “${query.trim()}”"

/** The line under the search while message text is still being searched; announced politely. */
@Composable
private fun PaletteSearching() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.xs)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .testTag(CommandPaletteTags.SEARCHING),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LettaSpinner(size = LettaDimens.Control.spinnerSm)
        Text(PALETTE_SEARCHING, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The palette's foot on a pointer host: the keys it answers to, each as a key cap. */
@Composable
private fun PaletteKeys() {
    LettaDivider()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm)
            .testTag(CommandPaletteTags.KEYS),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PALETTE_KEYS.forEach { (keys, action) -> KeyHint(keys, action) }
    }
}

@Composable
private fun KeyHint(keys: List<String>, action: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs), verticalAlignment = Alignment.CenterVertically) {
        keys.forEach { LettaKbd(it) }
        Text(action, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val PALETTE_KEYS: List<Pair<List<String>, String>> = listOf(
    listOf("↑", "↓") to "to move",
    listOf("Enter") to "to open",
    listOf("Esc") to "to close",
)

private const val PALETTE_SEARCHING = "Searching messages…"
private const val PALETTE_EMPTY_START = "Search for anything"
private const val PALETTE_EMPTY_HINT = "Conversations, message text, agents and pages"

private const val COMMAND_PALETTE_PLACEHOLDER = "Search conversations, messages, agents and pages"
