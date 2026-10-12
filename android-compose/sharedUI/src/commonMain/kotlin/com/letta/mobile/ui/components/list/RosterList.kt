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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.components.LettaEmptyHint
import com.letta.mobile.ui.components.LettaMenuItem
import com.letta.mobile.ui.components.LettaSectionLabel
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion

/**
 * letta-mobile-ckmub.3.10 (hard cut S7, primitive P4): the one sectioned, selectable list. Every
 * roster in the shell (the agent panel's chats and canvases, the rail's agent library, the archived
 * chats, the schedules rail) is this list with its own [row]s; a plain row is [RosterListRow].
 *
 * It owns what every list needs: section headers and empty hints, selection, the row menu through
 * the host (right-click on pointer, long-press on touch), hover buttons on pointer and a swipe on
 * touch, inline rename, a reorder hook, loading and empty states, and the keyboard: Up / Down move a
 * cursor, Enter opens, F2 renames, Alt+Up / Alt+Down reorder, Escape leaves the cursor.
 */
@Composable
fun <T> RosterList(
    spec: RosterSpec<T>,
    modifier: Modifier = Modifier,
    state: RosterListState = rememberRosterListState(),
    row: @Composable (item: T, row: RosterRowState) -> Unit,
) {
    val layout = remember(spec.sections) { RosterLayout.of(spec.sections, spec.key) }
    val listState = state.lazy
    var focused by remember { mutableStateOf(false) }
    val roster = RosterScope(layout, spec, state, focused)
    RevealCursor(state.cursor, layout, listState)
    LazyColumn(
        state = listState,
        modifier = modifier
            .onFocusChanged { focused = it.hasFocus }
            .onPreviewKeyEvent(roster::onKey)
            .focusable()
            .testTag(spec.options.testTag.value),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
    ) {
        if (layout.isEmpty && (spec.options.loading || spec.empty != null)) {
            item(key = "roster-state") { RosterEmptyState(spec.options, spec.empty) }
        } else {
            rosterLines(layout, roster, row)
        }
    }
}

private fun <T> LazyListScope.rosterLines(
    layout: RosterLayout<T>,
    roster: RosterScope<T>,
    row: @Composable (item: T, row: RosterRowState) -> Unit,
) {
    items(layout.lines, key = { it.key }) { line ->
        when (line) {
            is RosterLine.Header -> RosterHeader(line.section, roster.options.testTag)
            is RosterLine.Hint -> LettaEmptyHint(line.text)
            is RosterLine.Row -> Box(rosterRowMotion()) { RosterItem(line, roster, row) }
        }
    }
}

/** A row's placement motion: displaced rows glide to their new places. Off under reduced motion. */
@Composable
fun LazyItemScope.rosterRowMotion(): Modifier =
    if (LocalReducedMotion.current) Modifier else Modifier.animateItem()

/** Everything a row needs from its list, built once per composition. */
private class RosterScope<T>(
    val layout: RosterLayout<T>,
    spec: RosterSpec<T>,
    val state: RosterListState,
    val focused: Boolean,
) {
    val selection: RosterSelection<T> = spec.selection
    val actions: RosterActions<T> = spec.actions
    val options: RosterOptions = spec.options

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

    /** The row menu: the caller's entries, then rename, the touch-only quick actions, and the moves. */
    fun menu(line: RosterLine.Row<T>, row: RosterRowState, touch: Boolean): List<LettaMenuItem> = buildList {
        addAll(actions.menu(line.item, row))
        val renameLabel = actions.rename?.menuLabel
        val startRename = row.startRename
        if (renameLabel != null && startRename != null) add(LettaMenuItem(renameLabel, onClick = startRename))
        if (touch) actions.quick(line.item).forEach { add(LettaMenuItem(it.label, it.icon, onClick = it.onClick)) }
        if (actions.reorder != null) addAll(moveItems(line))
    }

    private fun moveItems(line: RosterLine.Row<T>): List<LettaMenuItem> = listOfNotNull(
        RosterKeys.reorderTarget(line, -1)?.let { LettaMenuItem(MOVE_UP) { reorder(line, -1) } },
        RosterKeys.reorderTarget(line, 1)?.let { LettaMenuItem(MOVE_DOWN) { reorder(line, 1) } },
    )
}

private const val MOVE_UP = "Move up"
private const val MOVE_DOWN = "Move down"

/** One row with its chrome: the rename field while renaming, else the menu, swipe and hover around [row]. */
@Composable
private fun <T> RosterItem(
    line: RosterLine.Row<T>,
    roster: RosterScope<T>,
    row: @Composable (item: T, row: RosterRowState) -> Unit,
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
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val highlighted = roster.state.cursor == line.key
    val rowState = RosterRowState(
        selected = roster.selection.isSelected(line.item),
        highlighted = highlighted,
        hovered = hovered,
        onClick = { roster.activate(line) },
        startRename = if (roster.canRename(line)) ({ roster.state.startRename(line.key) }) else null,
        interaction = interaction,
    )
    val host = LocalLettaHost.current
    val touch = host.input == LettaInput.Touch
    val menu = roster.menu(line, rowState, touch)
    val tag = roster.options.testTag.row(line.key)
    Box(
        Modifier
            .hoverable(interaction)
            .cursorRing(highlighted && roster.focused)
            .semantics { selected = rowState.selected }
            .testTag(tag.value),
    ) {
        val content: @Composable () -> Unit = {
            RosterGestures(RosterGestureSpec(tag, touch, hovered, roster.actions.swipe(line.item), roster.actions.quick(line.item))) {
                row(line.item, rowState)
            }
        }
        if (menu.isEmpty()) content() else host.rowMenu(menu, content)
    }
}

/** The keyboard cursor's ring, drawn only while the list has focus. */
@Composable
private fun Modifier.cursorRing(show: Boolean): Modifier =
    if (show) border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = CURSOR_ALPHA), MaterialTheme.shapes.small) else this

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
