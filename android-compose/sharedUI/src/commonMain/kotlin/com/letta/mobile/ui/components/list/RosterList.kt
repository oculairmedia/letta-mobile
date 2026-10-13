package com.letta.mobile.ui.components.list

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.letta.mobile.ui.components.LettaEmptyHint
import com.letta.mobile.ui.components.LettaMenuItem
import com.letta.mobile.ui.components.LettaSectionLabel
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.host.LocalRowHoldDrag
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion

/**
 * letta-mobile-ckmub.3.10 (hard cut S7, primitive P4): the one sectioned, selectable list. Every
 * roster in the shell (the agent panel's chats and canvases, the rail's agent library, the archived
 * chats, the schedules rail, memory files, channel cards, the background tasks) is this list with
 * its own [row]s; a plain row is [RosterListRow].
 *
 * It owns what every list needs: section headers and empty hints, selection, the row menu through
 * the host (right-click on pointer, long-press on touch), hover buttons on pointer and a swipe on
 * touch, inline rename, reorder (the keyboard, the menu's moves and a drag: a pointer drags a row,
 * a touch holds and then drags it), loading and empty states, an optional grid
 * ([RosterOptions.columns]) and the keyboard: Up / Down move a cursor, Enter opens, F2 renames,
 * Alt+Up / Alt+Down reorder, Escape leaves the cursor.
 */
@Composable
fun <T> RosterList(
    spec: RosterSpec<T>,
    modifier: Modifier = Modifier,
    state: RosterListState = rememberRosterListState(),
    row: RosterRowContent<T>,
) {
    val base = remember(spec.sections, spec.options.columns) { RosterLayout.of(spec.sections, spec.options.columns, spec.key) }
    val drag = remember { RosterDrag() }
    val layout = drag.layoutOver(base)
    val listState = state.lazy
    var focused by remember { mutableStateOf(false) }
    val gap = with(LocalDensity.current) { spec.options.spacing.toPx() }
    val roster = RosterScope(RosterLayouts(base, layout), spec, state, RosterFocus(focused, drag, gap))
    RevealCursor(state.cursor, layout, listState)
    LazyColumn(
        state = listState,
        modifier = modifier
            .onFocusChanged { focused = it.hasFocus }
            .onPreviewKeyEvent(roster::onKey)
            .focusable()
            .testTag(spec.options.testTag.value),
        contentPadding = spec.options.contentPadding,
        verticalArrangement = Arrangement.spacedBy(spec.options.spacing),
    ) {
        if (layout.isEmpty && spec.hasEmptyState()) {
            item(key = "roster-state") { RosterEmptyState(spec.options, spec.empty) }
        } else {
            rosterLines(roster, row)
        }
    }
}

private fun <T> LazyListScope.rosterLines(
    roster: RosterScope<T>,
    row: RosterRowContent<T>,
) {
    items(roster.layouts.shown.lines, key = { it.key }) { line -> RosterLineContent(line, roster, row) }
}

@Composable
private fun <T> LazyItemScope.RosterLineContent(
    line: RosterLine<T>,
    roster: RosterScope<T>,
    row: RosterRowContent<T>,
) {
    when (line) {
        is RosterLine.Header -> RosterHeader(line.section, roster.options.testTag)
        is RosterLine.Hint -> LettaEmptyHint(line.text)
        is RosterLine.Row -> RosterDraggableItem(line, roster, row)
        is RosterLine.Cells -> Box(rosterRowMotion()) { RosterCells(line, roster, row) }
    }
}

/** A list row: it glides when displaced, and while dragged it follows the pointer above its siblings. */
@Composable
private fun <T> LazyItemScope.RosterDraggableItem(
    line: RosterLine.Row<T>,
    roster: RosterScope<T>,
    row: RosterRowContent<T>,
) {
    val drag = roster.focus.drag
    val dragging = drag.key == line.key
    val motion = if (dragging) {
        Modifier
            .zIndex(1f)
            .graphicsLayer { translationY = drag.offset }
            .shadow(LettaDimens.Space.xs, MaterialTheme.shapes.small)
    } else {
        rosterRowMotion()
    }
    Box(motion) { RosterItem(line, roster, row) }
}

/** One grid line: its rows side by side, the last line padded out so every cell keeps its width. */
@Composable
private fun <T> RosterCells(
    line: RosterLine.Cells<T>,
    roster: RosterScope<T>,
    row: RosterRowContent<T>,
) {
    val columns = roster.options.columns
    Row(horizontalArrangement = Arrangement.spacedBy(roster.options.spacing)) {
        line.rows.forEach { cell -> Box(Modifier.weight(1f)) { RosterItem(cell, roster, row) } }
        repeat(columns - line.rows.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** A row's placement motion: displaced rows glide to their new places. Off under reduced motion. */
@Composable
fun LazyItemScope.rosterRowMotion(): Modifier {
    if (LocalReducedMotion.current) return Modifier
    return Modifier.animateItem()
}

/** The host's layout and the one on screen (the host's, with a dragged row in its preview place). */
private class RosterLayouts<T>(val base: RosterLayout<T>, val shown: RosterLayout<T>)

/** Whether the list has focus (the cursor ring shows), its drag, and the gap between rows in pixels. */
private class RosterFocus(val focused: Boolean, val drag: RosterDrag, val gap: Float)

/** Everything a row needs from its list, built once per composition. */
private class RosterScope<T>(
    val layouts: RosterLayouts<T>,
    spec: RosterSpec<T>,
    val state: RosterListState,
    val focus: RosterFocus,
) {
    val selection: RosterSelection<T> = spec.selection
    val actions: RosterActions<T> = spec.actions
    val options: RosterOptions = spec.options
    private val layout: RosterLayout<T> get() = layouts.shown

    fun onKey(event: KeyEvent): Boolean {
        // The rename field owns the keyboard while it is open.
        if (state.renaming != null) return false
        val command = RosterKeys.command(event) ?: return false
        val index = layout.rowIndex(state.cursor)
        val current = layout.rows.getOrNull(index)
        return when (command) {
            is RosterKeyCommand.MoveCursor -> moveCursor(index, command.delta)
            RosterKeyCommand.Activate -> current?.let { activate(it) } != null
            RosterKeyCommand.Rename -> current?.takeIf { canRename(it) }?.let { state.startRename(it.key) } != null
            is RosterKeyCommand.Reorder -> current?.let { reorder(it, command.delta) } ?: false
            RosterKeyCommand.ClearCursor -> (state.cursor != null).also { state.cursor = null }
        }
    }

    fun canRename(line: RosterLine.Row<T>): Boolean = actions.rename?.canRename?.invoke(line.item) == true

    /** What [line]'s row is right now: selection, the keyboard cursor, hover, its click, rename and drag. */
    fun rowState(line: RosterLine.Row<T>, interaction: MutableInteractionSource, hovered: Boolean): RosterRowState {
        val startRename: (() -> Unit)? = if (canRename(line)) ({ state.startRename(line.key) }) else null
        return RosterRowState(
            selected = selection.isSelected(line.item),
            highlighted = state.cursor == line.key,
            hovered = hovered,
            onClick = { activate(line) },
            startRename = startRename,
            interaction = interaction,
            dragging = focus.drag.key == line.key,
        )
    }

    /** A row drags when the host reorders, its section has somewhere to go, and the list is not a grid. */
    fun canDrag(line: RosterLine.Row<T>): Boolean {
        val listReorders = actions.reorder != null && options.columns == 1
        return listReorders && line.sectionSize > 1
    }

    private fun moveCursor(index: Int, delta: Int): Boolean {
        val next = RosterKeys.move(index, delta, layout.rows.size)
        state.cursor = layout.rows.getOrNull(next)?.key
        return next >= 0
    }

    fun activate(line: RosterLine.Row<T>) {
        state.cursor = line.key
        selection.onSelect(line.item)
    }

    fun reorder(line: RosterLine.Row<T>, delta: Int): Boolean {
        val hook = actions.reorder ?: return false
        val target = RosterKeys.reorderTarget(line, delta) ?: return true
        hook.onMove(line.item, target)
        return true
    }

    /** The drag callbacks of [line]'s row. */
    fun dragCallbacks(line: RosterLine.Row<T>): RosterDragCallbacks = RosterDragCallbacks(
        onStart = { startDrag(line) },
        onDrag = { delta -> focus.drag.dragBy(delta, layout, state.lazy, focus.gap) },
        onEnd = ::endDrag,
    )

    private fun startDrag(line: RosterLine.Row<T>) {
        val base = layouts.base
        base.rows.firstOrNull { it.key == line.key }?.let { focus.drag.start(it, base) }
    }

    /** Drops the dragged row and tells the host where it went, once. */
    private fun endDrag() {
        val hook = actions.reorder
        val (row, target) = focus.drag.drop(layouts.base) ?: return
        hook?.onMove(row.item, target)
    }

    /** The row menu: the caller's entries, then rename, the touch-only quick actions, and the moves. */
    fun menu(line: RosterLine.Row<T>, row: RosterRowState, touch: Boolean): List<LettaMenuItem> {
        val items = actions.menu(line.item, row).toMutableList()
        val renameLabel = actions.rename?.menuLabel
        val startRename = row.startRename
        if (renameLabel != null && startRename != null) items += LettaMenuItem(renameLabel, onClick = startRename)
        if (touch) items += actions.quick(line.item).map { LettaMenuItem(it.label, it.icon, onClick = it.onClick) }
        if (actions.reorder != null) items += moveItems(line)
        return items
    }

    private fun moveItems(line: RosterLine.Row<T>): List<LettaMenuItem> = listOfNotNull(
        RosterKeys.reorderTarget(line, -1)?.let { LettaMenuItem(MOVE_UP) { reorder(line, -1) } },
        RosterKeys.reorderTarget(line, 1)?.let { LettaMenuItem(MOVE_DOWN) { reorder(line, 1) } },
    )
}

private const val MOVE_UP = "Move up"
private const val MOVE_DOWN = "Move down"

/** One row with its chrome: the rename field while renaming, else the menu, drag, swipe and hover around [row]. */
@Composable
private fun <T> RosterItem(
    line: RosterLine.Row<T>,
    roster: RosterScope<T>,
    row: RosterRowContent<T>,
) {
    val rename = roster.actions.rename
    if (rename != null && roster.state.renaming == line.key) {
        RosterRenameField(
            RosterRenameRequest(
                title = rename.titleOf(line.item),
                onRename = { title -> rename.onRename(line.item, title) },
                onDone = roster.state::stopRename,
                placeholder = rename.placeholder,
            ),
            modifier = Modifier.testTag(rename.fieldTag),
        )
        return
    }
    RosterRowChrome(line, roster, row)
}

/** A row at rest: hover, the keyboard cursor's ring, selection, the drag, the menu, then the caller's [row]. */
@Composable
private fun <T> RosterRowChrome(
    line: RosterLine.Row<T>,
    roster: RosterScope<T>,
    row: RosterRowContent<T>,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val rowState = roster.rowState(line, interaction, hovered)
    val touch = LocalLettaHost.current.input == LettaInput.Touch
    val tag = roster.options.testTag.row(line.key)
    val drag = rememberRosterRowDrag(roster, line, touch)
    Box(
        Modifier
            .hoverable(interaction)
            .cursorRing(rowState.highlighted && roster.focus.focused)
            .rosterPointerDrag(enabled = drag.pointer, drag = drag.row)
            .semantics { selected = rowState.selected }
            .testTag(tag.value),
    ) {
        RosterRowMenu(roster.menu(line, rowState, touch), holdDrag = drag.hold) {
            RosterGestures(RosterGestureSpec(tag, touch, hovered, roster.actions.swipe(line.item), roster.actions.quick(line.item))) {
                row(line.item, rowState)
            }
        }
    }
}

/** A row's drag and how it starts: a mouse drag ([pointer]) or a touch hold ([hold], null when the row does not drag by hold). */
private class RosterRowDragInput(val row: RosterRowDrag, val pointer: Boolean, val hold: RosterRowDrag?)

/** [line]'s drag, fed the list's latest callbacks after each composition. */
@Composable
private fun <T> rememberRosterRowDrag(roster: RosterScope<T>, line: RosterLine.Row<T>, touch: Boolean): RosterRowDragInput {
    val rowDrag = remember { RosterRowDrag() }
    val callbacks = roster.dragCallbacks(line)
    SideEffect { rowDrag.callbacks = callbacks }
    val canDrag = roster.canDrag(line)
    return RosterRowDragInput(rowDrag, pointer = canDrag && !touch, hold = if (canDrag && touch) rowDrag else null)
}

/**
 * [content] inside the host's row menu (none without entries). With a [holdDrag] (a touch row that
 * reorders), the hold that opens the menu turns into a drag once the finger moves on.
 */
@Composable
private fun RosterRowMenu(menu: List<LettaMenuItem>, holdDrag: RosterRowDrag?, content: @Composable () -> Unit) {
    val host = LocalLettaHost.current
    when {
        menu.isEmpty() -> content()
        holdDrag != null -> CompositionLocalProvider(LocalRowHoldDrag provides holdDrag) { host.rowMenu(menu, content) }
        else -> host.rowMenu(menu, content)
    }
}

/** The keyboard cursor's ring, drawn only while the list has focus. */
@Composable
private fun Modifier.cursorRing(show: Boolean): Modifier {
    if (!show) return this
    return border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = CURSOR_ALPHA), MaterialTheme.shapes.small)
}

private const val CURSOR_ALPHA = 0.6f

@Composable
private fun <T> RosterHeader(section: RosterSection<T>, listTag: RosterListTag) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(listTag.header(section.key)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LettaSectionLabel(section.title.orEmpty(), Modifier.weight(1f))
        section.headerEnd?.let { end -> Box(Modifier.padding(top = LettaDimens.Space.md, end = LettaDimens.Space.xs)) { end() } }
    }
}

@Composable
private fun RosterEmptyState(options: RosterOptions, empty: (@Composable () -> Unit)?) {
    if (options.loading) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(LettaDimens.Space.sm).testTag(options.testTag.loading))
    } else {
        Box(Modifier.fillMaxWidth().testTag(options.testTag.empty)) { empty?.invoke() }
    }
}

/** Keeps the keyboard cursor's row in view. */
@Composable
private fun <T> RevealCursor(cursor: String?, layout: RosterLayout<T>, listState: LazyListState) {
    LaunchedEffect(cursor, layout) {
        val line = cursor?.let(layout::lineOf)?.takeIf { it >= 0 } ?: return@LaunchedEffect
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == line }
        val start = listState.layoutInfo.viewportStartOffset
        val end = listState.layoutInfo.viewportEndOffset
        when {
            info == null -> listState.scrollToItem(line)
            info.offset < start -> listState.scrollBy((info.offset - start).toFloat())
            info.offset + info.size > end -> listState.scrollBy((info.offset + info.size - end).toFloat())
        }
    }
}
