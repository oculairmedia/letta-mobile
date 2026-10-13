package com.letta.mobile.ui.shell.pages.conversations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.ui.components.LettaChipTab
import com.letta.mobile.ui.components.LettaEmptyHint
import com.letta.mobile.ui.components.LettaRefreshAction
import com.letta.mobile.ui.components.LettaRefreshable
import com.letta.mobile.ui.components.list.RosterActions
import com.letta.mobile.ui.components.list.RosterList
import com.letta.mobile.ui.components.list.RosterListTag
import com.letta.mobile.ui.components.list.RosterOptions
import com.letta.mobile.ui.components.list.RosterRename
import com.letta.mobile.ui.components.list.RosterSection
import com.letta.mobile.ui.components.list.RosterSections
import com.letta.mobile.ui.components.list.RosterSelection
import com.letta.mobile.ui.components.list.RosterSpec
import com.letta.mobile.ui.shell.sidebar.CHAT_NAME_PLACEHOLDER
import com.letta.mobile.ui.shell.sidebar.ShellArchiveFilter
import com.letta.mobile.ui.shell.sidebar.ShellArchivedChatsDialog
import com.letta.mobile.ui.shell.sidebar.ShellConversationDialogs
import com.letta.mobile.ui.shell.sidebar.ShellConversationListUi
import com.letta.mobile.ui.shell.sidebar.ShellConversationRow
import com.letta.mobile.ui.shell.sidebar.ShellConversationRowModel
import com.letta.mobile.ui.shell.sidebar.ShellConversationTags
import com.letta.mobile.ui.shell.sidebar.conversationRowActions
import com.letta.mobile.ui.shell.sidebar.shellConversationMenu
import com.letta.mobile.ui.theme.LettaDimens

/** Test tags of the conversations page. */
object ConversationsPageTags {
    const val PAGE = "conversations-page"
    const val LIST = "conversations-page-list"
    const val ARCHIVED = "conversations-page-archived"
    const val ERROR = "conversations-page-error"
    const val CREATE_AGENT = "conversations-page-create-agent"
    const val REFRESH = "conversations-page-refresh"

    fun filter(filter: ShellArchiveFilter) = "conversations-page-filter-${filter.name}"
}

/**
 * letta-mobile-ckmub.2.4 (hard cut S16): every agent's conversations in one roster, the page a chat
 * route without an agent opens. Composed from the shell's primitives only: the agent panel's
 * conversation rows ([ShellConversationRow], with its menu, rename, pin, fork and delete confirm), the
 * Active / Archived / All filter, the Archived chats dialog, all in one [RosterList] grouped by agent.
 * The body reloads through [LettaRefreshable] (letta-mobile-ckmub.3.22): a pull on touch, the filter
 * row's refresh on a pointer host. [modifier] carries the host frame's insets.
 */
@Composable
fun ConversationsPage(state: ConversationsPageState, actions: ConversationsPageActions, modifier: Modifier = Modifier) {
    Column(modifier.testTag(ConversationsPageTags.PAGE).padding(horizontal = LettaDimens.Space.md)) {
        val error = state.error
        when {
            error != null -> ConversationsPageRefreshable(actions) { ConversationsPageError(error, actions.onReload) }
            state.noAgents && !state.loading -> ConversationsPageRefreshable(actions) { ConversationsPageNoAgents(actions.onCreateAgent) }
            else -> ConversationsRoster(state, actions)
        }
    }
}

/** An error or empty body: a scrolling column, so a pull reaches the refresh. */
@Composable
private fun ColumnScope.ConversationsPageRefreshable(actions: ConversationsPageActions, content: @Composable () -> Unit) {
    LettaRefreshable(refreshing = false, onRefresh = actions.onReload, modifier = Modifier.fillMaxWidth().weight(1f)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() }
    }
}

@Composable
private fun ColumnScope.ConversationsRoster(state: ConversationsPageState, actions: ConversationsPageActions) {
    var showArchived by rememberSaveable { mutableStateOf(false) }
    ConversationsFilterRow(state.archiveFilter, actions, onShowArchived = { showArchived = true })
    val ui = remember { ShellConversationListUi() }
    val panel = actions.conversations
    val sections = remember(state.pinned, state.groups) { conversationSections(state) }
    LettaRefreshable(refreshing = false, onRefresh = actions.onReload, modifier = Modifier.fillMaxWidth().weight(1f)) {
        ConversationsRosterList(state, actions, sections, ui)
    }
    ShellConversationDialogs(ui, state.rows) { conversationRowActions(it, panel) }
    if (showArchived) {
        ShellArchivedChatsDialog(
            rows = state.archivedConversations,
            onRestore = { id -> panel.onArchiveConversation(id, false) },
            onOpen = { id ->
                showArchived = false
                panel.onConversationSelected(id)
            },
            onDismiss = { showArchived = false },
        )
    }
}

@Composable
private fun ConversationsRosterList(
    state: ConversationsPageState,
    actions: ConversationsPageActions,
    sections: List<RosterSection<ShellConversationRowModel>>,
    ui: ShellConversationListUi,
) {
    val panel = actions.conversations
    RosterList(
        RosterSpec(
            sections = sections,
            key = ShellConversationRowModel::id,
            selection = RosterSelection(isSelected = ShellConversationRowModel::selected, onSelect = { panel.onConversationSelected(it.id) }),
            actions = RosterActions(
                menu = { model, row -> shellConversationMenu(model, conversationRowActions(model, panel), ui, row) + actions.rowMenu(model) },
                rename = panel.onRenameConversation?.let(::conversationRename),
            ),
            options = RosterOptions(loading = state.loading, testTag = RosterListTag(ConversationsPageTags.LIST)),
            empty = { LettaEmptyHint(emptyHint(state.archiveFilter), Modifier.padding(LettaDimens.Space.md)) },
        ),
        modifier = Modifier.fillMaxSize(),
    ) { model, row -> ShellConversationRow(model, conversationRowActions(model, panel), row, ui) }
}

/** The pinned chats, then one section per agent headed by its name. */
internal fun conversationSections(state: ConversationsPageState): List<RosterSection<ShellConversationRowModel>> =
    listOfNotNull(
        RosterSection("pinned", state.pinned, title = RosterSections.PINNED_TITLE).takeIf { state.pinned.isNotEmpty() },
    ) + state.groups.map { group -> RosterSection("agent-${group.agentId}", group.rows, title = group.agentName) }

private fun conversationRename(rename: (id: String, title: String) -> Unit) = RosterRename<ShellConversationRowModel>(
    titleOf = ShellConversationRowModel::title,
    onRename = { model, title -> rename(model.id, title) },
    // The row's own menu carries "Rename chat".
    menuLabel = null,
    placeholder = CHAT_NAME_PLACEHOLDER,
    fieldTag = ShellConversationTags.RENAME_FIELD,
)

private fun emptyHint(filter: ShellArchiveFilter): String = when (filter) {
    ShellArchiveFilter.Archived -> "No archived chats"
    else -> "No chats yet"
}

/** Active / Archived / All, then the pointer host's refresh and the Archived chats dialog's button. */
@Composable
private fun ConversationsFilterRow(filter: ShellArchiveFilter, actions: ConversationsPageActions, onShowArchived: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = LettaDimens.Space.xs),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShellArchiveFilter.entries.forEach { entry ->
            LettaChipTab(
                text = entry.label,
                active = filter == entry,
                onClick = { actions.conversations.onArchiveFilterChange(entry) },
                modifier = Modifier.testTag(ConversationsPageTags.filter(entry)),
            )
        }
        Spacer(Modifier.weight(1f))
        LettaRefreshAction(refreshing = false, onRefresh = actions.onReload, modifier = Modifier.testTag(ConversationsPageTags.REFRESH))
        TextButton(onClick = onShowArchived, modifier = Modifier.testTag(ConversationsPageTags.ARCHIVED)) { Text("Archived chats") }
    }
}

@Composable
private fun ConversationsPageError(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(LettaDimens.Space.lg).testTag(ConversationsPageTags.ERROR)) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun ConversationsPageNoAgents(onCreateAgent: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(LettaDimens.Space.lg)) {
        Text("No agents yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Create an agent to start your first chat.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        onCreateAgent?.let { create ->
            TextButton(onClick = create, modifier = Modifier.testTag(ConversationsPageTags.CREATE_AGENT)) { Text("Create agent") }
        }
    }
}
