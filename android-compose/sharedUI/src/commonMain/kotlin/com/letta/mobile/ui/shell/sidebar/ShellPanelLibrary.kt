package com.letta.mobile.ui.shell.sidebar

import com.letta.mobile.ui.components.LocalObjectVerbs
import com.letta.mobile.ui.components.ObjectVerbMenus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import com.letta.mobile.data.lens.WorkPlayLens
import com.letta.mobile.ui.components.LettaChipTab
import com.letta.mobile.ui.components.LettaMenuItem
import com.letta.mobile.ui.theme.LettaSectionLabel
import com.letta.mobile.ui.components.list.RosterActions
import com.letta.mobile.ui.components.list.RosterLayout
import com.letta.mobile.ui.components.list.RosterList
import com.letta.mobile.ui.components.list.RosterListTag
import com.letta.mobile.ui.components.list.RosterOptions
import com.letta.mobile.ui.components.list.RosterRename
import com.letta.mobile.ui.components.list.RosterRowState
import com.letta.mobile.ui.components.list.RosterSection
import com.letta.mobile.ui.components.list.RosterSelection
import com.letta.mobile.ui.components.list.RosterSpec
import com.letta.mobile.ui.components.list.rememberRosterListState
import com.letta.mobile.ui.shell.ShellRowMenus
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.shell_canvas_use_own
import org.jetbrains.compose.resources.stringResource

/** One row of the agent panel's roster: a conversation or a canvas. */
@Immutable
internal sealed interface ShellPanelItem {
    val key: String
    val selected: Boolean

    data class Chat(val model: ShellConversationRowModel) : ShellPanelItem {
        override val key: String get() = "chat-${model.id}"
        override val selected: Boolean get() = model.selected
    }

    /** Selected while open in a pane, or while it is the board the conversation in canvas mode shows. */
    data class Canvas(val model: ShellCanvasRowModel) : ShellPanelItem {
        override val key: String get() = "canvas-${model.id.value}"
        override val selected: Boolean get() = model.selected || model.mounted
    }
}

/**
 * The panel's two sections: the conversations (their header sits above, with the filter), then
 * the canvases, which end their header with [canvasHeaderEnd] (New canvas) when the host has one.
 */
internal fun shellPanelSections(
    state: ShellAgentPanelState,
    canvasHeaderEnd: (@Composable () -> Unit)? = null,
): List<RosterSection<ShellPanelItem>> = listOf(
    RosterSection(key = "chats", items = state.conversations.map(ShellPanelItem::Chat), emptyHint = "No chats"),
    RosterSection(
        key = CANVASES_SECTION,
        items = state.canvases.map(ShellPanelItem::Canvas),
        title = "Canvases",
        emptyHint = "No canvases",
        headerEnd = canvasHeaderEnd,
    ),
)

/** Where the Canvases header sits in the one list, for the jump; -1 when it is not drawn. */
internal fun canvasesLine(sections: List<RosterSection<ShellPanelItem>>): Int =
    RosterLayout.of(sections, key = ShellPanelItem::key).lines.indexOfFirst { it.key == "section:$CANVASES_SECTION" }

/**
 * The conversations under their Active / Archived / All filter, then the canvases: one
 * [RosterList]. A pinned or settled chat that moves glides to its place. In canvas mode a
 * canvas pick mounts it for the conversation on screen ([ShellCanvasPicks]); the mounted one is
 * marked current and offers the way back to the conversation's own canvas.
 */
@Composable
internal fun ColumnScope.ShellPanelLibrary(state: ShellAgentPanelState, actions: ShellAgentPanelActions) {
    val picker = rememberCanvasPickerState()
    val picks = rememberCanvasPicks(actions)
    val canvases = remember(state.canvases, picker.mounted) { markMounted(state.canvases, picker.mounted) }
    val sections = remember(state.conversations, canvases, actions.onNewCanvas) {
        shellPanelSections(state.copy(canvases = canvases), actions.onNewCanvas?.let { { NewCanvasButton(it) } })
    }
    val rosterState = rememberRosterListState()
    val scope = rememberCoroutineScope()
    LettaSectionLabel(WorkPlayLens.conversationsHeader(state.mode))
    ShellLibraryFilters(state, actions) {
        if (state.conversations.isNotEmpty()) {
            CanvasJumpButton {
                val line = canvasesLine(sections)
                if (line >= 0) scope.launch { rosterState.lazy.animateScrollToItem(line) }
            }
        }
    }
    val verbs = LocalObjectVerbs.current
    val ui = remember { ShellConversationListUi() }
    val useOwnLabel = stringResource(Res.string.shell_canvas_use_own).takeIf { picker.mountsOnPick }
    val rows = ShellPanelRows(actions, picks, ui, useOwnLabel)
    RosterList(
        RosterSpec(
            sections = sections,
            key = ShellPanelItem::key,
            selection = RosterSelection(isSelected = ShellPanelItem::selected, onSelect = rows::open),
            actions = RosterActions(
                menu = { item, row -> item.panelItemMenu(row, rows, verbs) },
                rename = actions.onRenameConversation?.let(::chatRename),
            ),
            options = RosterOptions(testTag = RosterListTag(ShellAgentPanelTags.LIBRARY)),
        ),
        modifier = Modifier.fillMaxWidth().weight(1f),
        state = rosterState,
    ) { item, row -> ShellPanelItemRow(item, row, rows) }
    ShellConversationDialogs(ui, state.conversations) { conversationRowActions(it, actions) }
}

/** The Active / Archived / All chips, then [end] (the jump to the canvases). */
@Composable
private fun ShellLibraryFilters(state: ShellAgentPanelState, actions: ShellAgentPanelActions, end: @Composable () -> Unit) {
    Row(
        modifier = Modifier.padding(start = LettaDimens.Space.xs, top = LettaDimens.Space.hair, bottom = LettaDimens.Space.sm),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShellArchiveFilter.entries.forEach { filter ->
            LettaChipTab(text = filter.label, active = state.archiveFilter == filter, onClick = { actions.onArchiveFilterChange(filter) })
        }
        Spacer(Modifier.weight(1f))
        end()
    }
}

/** What the library's rows do: the host's actions, the window's canvas picks, and the list's dialogs. */
private class ShellPanelRows(
    val actions: ShellAgentPanelActions,
    val picks: ShellCanvasPicks,
    val ui: ShellConversationListUi,
    /** The way back's label while a pick mounts (canvas mode on a host with mounts); null hides the way back. */
    val useOwnLabel: String?,
) {
    fun open(item: ShellPanelItem) {
        when (item) {
            is ShellPanelItem.Chat -> actions.onConversationSelected(item.model.id)
            is ShellPanelItem.Canvas -> picks.open(item.model.id)
        }
    }

    /** The mounted row's way back to the conversation's own canvas; null on every other row. */
    fun useOwn(canvas: ShellCanvasRowModel): (() -> Unit)? = picks::useOwn.takeIf { canvas.mounted && useOwnLabel != null }

    /** The mounted row's menu entry for the way back; null on every other row. */
    fun useOwnItem(canvas: ShellCanvasRowModel): LettaMenuItem? =
        useOwn(canvas)?.let { action -> useOwnLabel?.let { LettaMenuItem(it, onClick = action) } }
}

@Composable
private fun ShellPanelItemRow(item: ShellPanelItem, row: RosterRowState, rows: ShellPanelRows) {
    when (item) {
        is ShellPanelItem.Chat -> ShellConversationRow(item.model, conversationRowActions(item.model, rows.actions), row, rows.ui)
        is ShellPanelItem.Canvas -> ShellCanvasRow(
            item.model, row, ShellCanvasRowActions(canvasArchiveToggle(item.model, rows.actions), rows.useOwn(item.model)),
        )
    }
}

private fun ShellPanelItem.panelItemMenu(row: RosterRowState, rows: ShellPanelRows, verbs: ObjectVerbMenus?): List<LettaMenuItem> =
    when (this) {
        is ShellPanelItem.Chat -> model.shellConversationMenu(conversationRowActions(model, rows.actions), rows.ui, row, verbs)
        is ShellPanelItem.Canvas -> ShellRowMenus.canvas(model.archived, canvasArchiveToggle(model, rows.actions), rows.useOwnItem(model))
    }

/** Rename in place: the chat's own menu entry ("Rename chat") opens it, as F2 does. */
private fun chatRename(rename: (id: String, title: String) -> Unit) = RosterRename<ShellPanelItem>(
    titleOf = { (it as? ShellPanelItem.Chat)?.model?.title.orEmpty() },
    onRename = { item, title -> (item as? ShellPanelItem.Chat)?.let { rename(it.model.id, title) } },
    menuLabel = null,
    placeholder = CHAT_NAME_PLACEHOLDER,
    fieldTag = ShellConversationTags.RENAME_FIELD,
    canRename = { it is ShellPanelItem.Chat },
)

private fun canvasArchiveToggle(canvas: ShellCanvasRowModel, actions: ShellAgentPanelActions): (() -> Unit)? =
    actions.onArchiveCanvas?.let { archive -> { archive(canvas.id, !canvas.archived) } }

/** One conversation's actions, bound to it. */
internal fun conversationRowActions(conversation: ShellConversationRowModel, actions: ShellAgentPanelActions) =
    ShellConversationRowActions(
        onArchiveToggle = { actions.onArchiveConversation(conversation.id, !conversation.archived) },
        onDelete = { actions.onDeleteConversation(conversation.id) },
        deleteBehavior = actions.deleteBehavior,
        onRename = actions.onRenameConversation?.let { rename -> { title -> rename(conversation.id, title) } },
        marks = actions.conversationMarks,
        onFork = actions.onForkConversation?.let { fork -> { name -> fork(conversation.id, name) } },
    )
