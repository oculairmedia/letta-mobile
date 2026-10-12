package com.letta.mobile.ui.shell.sidebar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.letta.mobile.data.lens.WorkPlayLens
import com.letta.mobile.ui.components.LettaChipTab
import com.letta.mobile.ui.components.LettaMenuItem
import com.letta.mobile.ui.components.LettaSectionLabel
import com.letta.mobile.ui.components.list.RosterActions
import com.letta.mobile.ui.components.list.RosterList
import com.letta.mobile.ui.components.list.RosterOptions
import com.letta.mobile.ui.components.list.RosterRename
import com.letta.mobile.ui.components.list.RosterRowState
import com.letta.mobile.ui.components.list.RosterSection
import com.letta.mobile.ui.components.list.RosterSelection
import com.letta.mobile.ui.components.list.RosterSpec
import com.letta.mobile.ui.shell.ShellRowMenus
import com.letta.mobile.ui.theme.LettaDimens

/** One row of the agent panel's roster: a conversation or a canvas. */
@Immutable
internal sealed interface ShellPanelItem {
    val key: String
    val selected: Boolean

    data class Chat(val model: ShellConversationRowModel) : ShellPanelItem {
        override val key: String get() = "chat-${model.id}"
        override val selected: Boolean get() = model.selected
    }

    data class Canvas(val model: ShellCanvasRowModel) : ShellPanelItem {
        override val key: String get() = "canvas-${model.id.value}"
        override val selected: Boolean get() = model.selected
    }
}

/** The panel's two sections: the conversations (their header sits above, with the filter), then the canvases. */
internal fun shellPanelSections(state: ShellAgentPanelState): List<RosterSection<ShellPanelItem>> = listOf(
    RosterSection(key = "chats", items = state.conversations.map(ShellPanelItem::Chat), emptyHint = "No chats"),
    RosterSection(key = "canvases", items = state.canvases.map(ShellPanelItem::Canvas), title = "Canvases", emptyHint = "No canvases"),
)

/**
 * The conversations under their Active / Archived / All filter, then the canvases: one
 * [RosterList] (letta-mobile-ckmub.3.10). A pinned or settled chat that moves glides to its place.
 */
@Composable
internal fun ColumnScope.ShellPanelLibrary(state: ShellAgentPanelState, actions: ShellAgentPanelActions) {
    LettaSectionLabel(WorkPlayLens.conversationsHeader(state.mode))
    Row(
        modifier = Modifier.padding(start = LettaDimens.Space.xs, top = LettaDimens.Space.hair, bottom = LettaDimens.Space.sm),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        ShellArchiveFilter.entries.forEach { filter ->
            LettaChipTab(text = filter.label, active = state.archiveFilter == filter, onClick = { actions.onArchiveFilterChange(filter) })
        }
    }
    val ui = remember { ShellConversationListUi() }
    val sections = remember(state.conversations, state.canvases) { shellPanelSections(state) }
    RosterList(
        RosterSpec(
            sections = sections,
            key = ShellPanelItem::key,
            selection = RosterSelection(isSelected = ShellPanelItem::selected, onSelect = { openPanelItem(it, actions) }),
            actions = RosterActions(
                menu = { item, row -> panelItemMenu(item, row, actions, ui) },
                rename = actions.onRenameConversation?.let(::chatRename),
            ),
            options = RosterOptions(testTag = ShellAgentPanelTags.LIBRARY),
        ),
        modifier = Modifier.fillMaxWidth().weight(1f),
    ) { item, row -> ShellPanelItemRow(item, row, actions, ui) }
    ShellConversationDialogs(ui, state.conversations) { conversationRowActions(it, actions) }
}

@Composable
private fun ShellPanelItemRow(item: ShellPanelItem, row: RosterRowState, actions: ShellAgentPanelActions, ui: ShellConversationListUi) {
    when (item) {
        is ShellPanelItem.Chat -> ShellConversationRow(item.model, conversationRowActions(item.model, actions), row, ui)
        is ShellPanelItem.Canvas -> ShellCanvasRow(item.model, row, canvasArchiveToggle(item.model, actions))
    }
}

private fun openPanelItem(item: ShellPanelItem, actions: ShellAgentPanelActions) {
    when (item) {
        is ShellPanelItem.Chat -> actions.onConversationSelected(item.model.id)
        is ShellPanelItem.Canvas -> actions.onOpenCanvas(item.model.id)
    }
}

private fun panelItemMenu(
    item: ShellPanelItem,
    row: RosterRowState,
    actions: ShellAgentPanelActions,
    ui: ShellConversationListUi,
): List<LettaMenuItem> = when (item) {
    is ShellPanelItem.Chat -> shellConversationMenu(item.model, conversationRowActions(item.model, actions), ui, row)
    is ShellPanelItem.Canvas -> ShellRowMenus.canvas(item.model.archived, canvasArchiveToggle(item.model, actions))
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
        onPinToggle = actions.onPinConversation?.let { pin -> { pin(conversation.id, !conversation.pinned) } },
        marks = actions.conversationMarks,
        onFork = actions.onForkConversation?.let { fork -> { name -> fork(conversation.id, name) } },
    )
