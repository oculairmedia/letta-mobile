package com.letta.mobile.ui.components.picker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.components.LettaListRow
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.components.LettaSectionLabel
import com.letta.mobile.ui.host.LettaPane
import com.letta.mobile.ui.host.LettaPaneKind
import com.letta.mobile.ui.host.PaneSize
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/**
 * letta-mobile-ckmub.3.6 (hard cut S8, primitive P5): what a picker shows. [selected] holds item
 * keys; [filterLocally] false means the caller already filtered the sections for [query] (a
 * controller that searches); a null [search] hides the search field.
 */
@Immutable
data class LettaPickerSpec<T>(
    val sections: List<PickerSection<T>>,
    val query: String = "",
    val mode: PickMode = PickMode.Single,
    val selected: Set<String> = emptySet(),
    val load: PickerLoad = PickerLoad.Ready,
    val filterLocally: Boolean = true,
    val search: PickerSearch? = PickerSearch(),
    /** What an empty list says; null uses "No matches" / "Nothing here yet". */
    val empty: PickerEmpty? = null,
    /** The second level being shown, with its back arrow; null at the root. */
    val level: PickerLevel? = null,
    val tags: PickerTags = PickerTags(),
)

/** The pinned search field's placeholder and whether it takes the caret on open. */
@Immutable
data class PickerSearch(val placeholder: String = LettaPickerStrings.SEARCH, val autoFocus: Boolean = true)

/** What a picker reports. A pick never closes the picker by itself: the caller decides. */
@Immutable
class PickerCallbacks<T>(
    val onQuery: (String) -> Unit,
    val onPick: (PickerItem<T>) -> Unit,
    /** Escape at the root (inside a pane, the pane's own dismissal runs instead). */
    val onDismiss: () -> Unit = {},
    val onToggleSection: ((PickerSection<T>) -> Unit)? = null,
    /** Sees each key press first, with the highlighted row; true consumes it (Ctrl+Enter in quick query). */
    val onKey: ((KeyEvent, PickerItem<T>?) -> Boolean)? = null,
)

/** How one row is drawn by a custom [PickerSlots.row]. */
@Immutable
data class PickerRowState(val selected: Boolean, val highlighted: Boolean, val mode: PickMode, val onClick: () -> Unit)

/** The parts a surface draws itself: its own rows, a leading glyph, a band under the search, a footer. */
@Immutable
class PickerSlots<T>(
    val row: (@Composable (PickerItem<T>, PickerRowState) -> Unit)? = null,
    val leading: (@Composable (PickerItem<T>) -> Unit)? = null,
    val header: (@Composable ColumnScope.() -> Unit)? = null,
    val footer: (@Composable ColumnScope.() -> Unit)? = null,
)

/** Which container the picker opens in. [LettaPaneKind.Modal]: a sheet on touch, a dialog on pointer. */
@Immutable
data class PickerPane(
    val kind: LettaPaneKind = LettaPaneKind.Modal,
    val title: String? = null,
    val size: PaneSize = PaneSize.Default,
    val testTag: String? = null,
)

/**
 * A picker in its own [LettaPane]. Dismissing the pane (Escape, back, scrim) first leaves a
 * second level when [LettaPickerSpec.level] is set.
 */
@Composable
fun <T> LettaPicker(
    spec: LettaPickerSpec<T>,
    callbacks: PickerCallbacks<T>,
    pane: PickerPane = PickerPane(),
    slots: PickerSlots<T> = PickerSlots(),
) {
    val level = spec.level
    LettaPane(
        kind = pane.kind,
        onDismiss = { if (level != null) level.onBack() else callbacks.onDismiss() },
        title = pane.title,
        size = pane.size,
        testTag = pane.testTag,
    ) {
        LettaPickerContent(spec, callbacks, slots = slots)
    }
}

/**
 * The picker's body for a pane that already exists: an optional back header, the pinned search,
 * the sectioned list with its own scroll (it takes the height left, never nested in a scroll),
 * then the load error and [PickerSlots.footer]. Up/Down move the highlight, Enter picks it,
 * Escape goes back a level or dismisses.
 */
@Composable
fun <T> ColumnScope.LettaPickerContent(
    spec: LettaPickerSpec<T>,
    callbacks: PickerCallbacks<T>,
    modifier: Modifier = Modifier,
    slots: PickerSlots<T> = PickerSlots(),
) {
    val layout = remember(spec.sections, spec.query, spec.filterLocally) { PickerLayout.of(spec.sections, spec.query, spec.filterLocally) }
    val highlight = remember(spec.query, layout.rows.size) { mutableIntStateOf(PickerKeys.initial(spec.mode, spec.query, layout.rows.size)) }
    val listState = rememberLazyListState()
    LaunchedEffect(highlight.intValue, layout) {
        if (highlight.intValue != PickerKeys.NONE) listState.reveal(layout.lineOf(highlight.intValue))
    }
    val keys = PickerKeyHandler(layout, highlight, spec.level, callbacks)
    Column(modifier.weight(1f, fill = false).onPreviewKeyEvent(keys::handle).testTag(spec.tags.root)) {
        spec.level?.let { PickerBackHeader(it) }
        spec.search?.let { search ->
            LettaPickerSearchField(spec.query, callbacks.onQuery, search, fieldModifier = Modifier.testTag(spec.tags.search))
        }
        slots.header?.invoke(this)
        if (spec.load == PickerLoad.Loading && !layout.isEmpty) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag(LettaPickerTags.LOADING))
        }
        PickerList(PickerListModel(spec, layout, highlight.intValue, listState), callbacks, slots, Modifier.weight(1f, fill = false))
        (spec.load as? PickerLoad.Failed)?.let { PickerError(it.message, spec.tags.error) }
        slots.footer?.invoke(this)
    }
}

/** Arrow keys move the highlight, Enter picks it, Escape leaves the level or the picker. */
private class PickerKeyHandler<T>(
    private val layout: PickerLayout<T>,
    private val highlight: MutableIntState,
    private val level: PickerLevel?,
    private val callbacks: PickerCallbacks<T>,
) {
    fun handle(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val current = layout.rows.getOrNull(highlight.intValue)
        if (callbacks.onKey?.invoke(event, current) == true) return true
        return when (event.key) {
            Key.DirectionDown -> move(1)
            Key.DirectionUp -> move(-1)
            Key.Enter, Key.NumPadEnter -> pick(current)
            Key.Escape -> {
                if (level != null) level.onBack() else callbacks.onDismiss()
                true
            }
            else -> false
        }
    }

    private fun pick(item: PickerItem<T>?): Boolean {
        item ?: return false
        callbacks.onPick(item)
        return true
    }

    private fun move(delta: Int): Boolean {
        highlight.intValue = PickerKeys.move(highlight.intValue, delta, layout.rows.size)
        return true
    }
}

/** Everything the list draws from one composition: the spec, its lines, the highlighted row, the scroll. */
@Immutable
private class PickerListModel<T>(
    val spec: LettaPickerSpec<T>,
    val layout: PickerLayout<T>,
    val highlight: Int,
    val listState: LazyListState,
)

@Composable
private fun <T> PickerList(model: PickerListModel<T>, callbacks: PickerCallbacks<T>, slots: PickerSlots<T>, modifier: Modifier) {
    val spec = model.spec
    LazyColumn(
        state = model.listState,
        contentPadding = PaddingValues(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs),
        modifier = modifier.fillMaxWidth().testTag(spec.tags.list),
    ) {
        if (model.layout.isEmpty) emptyLine(spec)
        items(model.layout.lines, key = { it.key }) { line ->
            when (line) {
                is PickerLine.Header -> PickerSectionHeader(line.section, spec.tags, callbacks.onToggleSection)
                is PickerLine.Row -> PickerRow(line, model, callbacks, slots)
            }
        }
    }
}

@Composable
private fun <T> PickerRow(line: PickerLine.Row<T>, model: PickerListModel<T>, callbacks: PickerCallbacks<T>, slots: PickerSlots<T>) {
    val item = line.item
    val state = PickerRowState(
        selected = item.key in model.spec.selected,
        highlighted = line.index == model.highlight,
        mode = model.spec.mode,
        onClick = { callbacks.onPick(item) },
    )
    val custom = slots.row
    if (custom != null) custom(item, state) else DefaultPickerRow(item, state, slots.leading, model.spec.tags)
}

private fun <T> LazyListScope.emptyLine(spec: LettaPickerSpec<T>) {
    item(key = "empty") {
        val empty = spec.empty ?: PickerEmpty(
            if (spec.query.isBlank()) LettaPickerStrings.NOTHING_HERE else LettaPickerStrings.noMatch(spec.query.trim()),
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(LettaDimens.Space.lg).testTag(spec.tags.empty),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        ) {
            if (spec.load == PickerLoad.Loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag(LettaPickerTags.LOADING))
            if (empty.title.isNotEmpty()) {
                Text(empty.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            empty.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** The stock row: the shared list row, with a check (single), a checkbox (multi) or a chevron (drill-in). */
@Composable
private fun <T> DefaultPickerRow(item: PickerItem<T>, state: PickerRowState, leading: (@Composable (PickerItem<T>) -> Unit)?, tags: PickerTags) {
    LettaListRow(
        spec = LettaListRowSpec(item.title, item.icon, item.trailing, selected = state.highlighted, subtitle = item.subtitle),
        onClick = state.onClick,
        modifier = Modifier.pickerRowSemantics(state).testTag(item.testTag ?: (tags.rowPrefix + item.key)),
        leading = leading?.let { draw -> { draw(item) } },
        end = { RowEnd(item, state) },
    )
}

@Composable
private fun RowEnd(item: PickerItem<*>, state: PickerRowState) {
    when {
        item.drillIn -> Icon(LettaIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon))
        state.mode == PickMode.Multi -> Checkbox(checked = state.selected, onCheckedChange = null)
        state.mode == PickMode.Single && state.selected -> Icon(
            imageVector = LettaIcons.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(LettaDimens.Control.icon),
        )
    }
}

/** A single pick reads as a radio choice, a multi pick as a checkbox, a command as a button. */
fun Modifier.pickerRowSemantics(state: PickerRowState): Modifier = semantics {
    when (state.mode) {
        PickMode.Single -> {
            role = Role.RadioButton
            selected = state.selected
        }
        PickMode.Multi -> {
            role = Role.Checkbox
            toggleableState = ToggleableState(state.selected)
        }
        PickMode.Command -> role = Role.Button
    }
}

@Composable
private fun <T> PickerSectionHeader(section: PickerSection<T>, tags: PickerTags, onToggle: ((PickerSection<T>) -> Unit)?) {
    val collapsed = section.collapsed
    val toggle: (() -> Unit)? = onToggle?.takeIf { collapsed != null }?.let { { it(section) } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (toggle != null) Modifier.clickable(onClick = toggle) else Modifier)
            .semantics {
                heading()
                if (collapsed != null) stateDescription = if (collapsed) "Collapsed" else "Expanded"
            }
            .padding(end = LettaDimens.Space.sm)
            .testTag(tags.sectionPrefix + section.key),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LettaSectionLabel(section.title.orEmpty(), Modifier.weight(1f))
        if (collapsed != null) DisclosureChevron(expanded = !collapsed, compact = true)
    }
}

@Composable
private fun PickerBackHeader(level: PickerLevel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = level.onBack, modifier = Modifier.testTag(LettaPickerTags.BACK)) {
            Icon(LettaIcons.ArrowBack, contentDescription = LettaPickerStrings.BACK)
        }
        Text(level.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PickerError(message: String, tag: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.xs).testTag(tag),
    )
}

/** Scrolls just enough to show list line [line] whole; a line out of view is scrolled to the top. */
private suspend fun LazyListState.reveal(line: Int) {
    val start = layoutInfo.viewportStartOffset
    val end = layoutInfo.viewportEndOffset
    val info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == line }
    when {
        info == null -> scrollToItem(line)
        info.offset < start -> scrollBy((info.offset - start).toFloat())
        info.offset + info.size > end -> scrollBy((info.offset + info.size - end).toFloat())
    }
}
