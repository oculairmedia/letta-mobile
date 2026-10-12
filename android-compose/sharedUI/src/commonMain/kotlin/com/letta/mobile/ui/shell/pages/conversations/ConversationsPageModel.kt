package com.letta.mobile.ui.shell.pages.conversations

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import com.letta.mobile.ui.components.LettaMenuItem
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelActions
import com.letta.mobile.ui.shell.sidebar.ShellArchiveFilter
import com.letta.mobile.ui.shell.sidebar.ShellConversationMarks
import com.letta.mobile.ui.shell.sidebar.ShellConversationRowModel
import com.letta.mobile.ui.shell.sidebar.ShellRelativeTime
import com.letta.mobile.ui.shell.sidebar.ShellSidebarMapping
import kotlin.time.Instant

/** One agent's conversations on the fleet conversations page, headed by the agent's name. */
@Immutable
data class ConversationsPageGroup(
    val agentId: String,
    val agentName: String,
    val rows: List<ShellConversationRowModel>,
)

/**
 * letta-mobile-ckmub.2.4 (hard cut S16): everything the fleet conversations page draws. Rows are the
 * agent panel's conversation rows ([ShellConversationRowModel]); the pinned ones lead under "Pinned",
 * the rest are grouped by agent, most recently active agent first.
 */
@Immutable
data class ConversationsPageState(
    val pinned: List<ShellConversationRowModel> = emptyList(),
    val groups: List<ConversationsPageGroup> = emptyList(),
    val archiveFilter: ShellArchiveFilter = ShellArchiveFilter.Active,
    /** Every archived chat, whatever the filter, for the Archived chats dialog. */
    val archivedConversations: List<ShellConversationRowModel> = emptyList(),
    val loading: Boolean = false,
    /** A load failure with nothing cached to show. */
    val error: String? = null,
    /** The backend has no agents yet: the empty page offers to create the first. */
    val noAgents: Boolean = false,
) {
    /** Every row the page shows, in order. */
    val rows: List<ShellConversationRowModel> get() = pinned + groups.flatMap { it.rows }
}

/**
 * What the page asks its host to do. [conversations] are the agent panel's own conversation actions
 * (open, archive, delete, rename, pin, fork, the archive filter), keyed by conversation id; [rowMenu]
 * adds host entries after a row's shared menu (Android's conversation inspector).
 */
data class ConversationsPageActions(
    val conversations: ShellAgentPanelActions = ShellAgentPanelActions(),
    val onRetry: () -> Unit = {},
    val onCreateAgent: (() -> Unit)? = null,
    val rowMenu: (ShellConversationRowModel) -> List<LettaMenuItem> = { emptyList() },
)

/** What a host knows about the fleet's conversations, in platform-neutral terms. */
data class ConversationsPageInput(
    /** Every conversation, most recent first; [ChatConversationSummary.agentId] groups them. */
    val conversations: List<ChatConversationSummary> = emptyList(),
    val archiveFilter: ShellArchiveFilter = ShellArchiveFilter.Active,
    val pinnedIds: Set<String> = emptySet(),
    /** Conversations with a run in flight: their rows pulse. */
    val workingIds: Set<String> = emptySet(),
    val loading: Boolean = false,
    val error: String? = null,
    val noAgents: Boolean = false,
)

/** Pure mapping from [ConversationsPageInput] to [ConversationsPageState]. */
object ConversationsPageMapping {
    fun state(input: ConversationsPageInput, now: Instant): ConversationsPageState {
        val timeLabel = { raw: String -> ShellRelativeTime.compact(raw, now) }
        val marks = ShellConversationMarks(pinnedIds = input.pinnedIds)
        val visible = input.conversations.filter { input.archiveFilter.admits(it.archived) }
        val rows = ShellSidebarMapping.conversationRows(visible, marks, timeLabel)
            .map { it.copy(thinking = it.id in input.workingIds) }
        val (pinned, rest) = rows.partition { it.pinned }
        val archived = input.conversations.filter { it.archived }
        return ConversationsPageState(
            pinned = pinned,
            groups = groupByAgent(rest, input.conversations),
            archiveFilter = input.archiveFilter,
            archivedConversations = ShellSidebarMapping.conversationRows(archived, marks, timeLabel),
            loading = input.loading,
            error = input.error.takeIf { input.conversations.isEmpty() },
            noAgents = input.noAgents,
        )
    }

    /** [rows] in their order, split by agent; an agent's group sits where its first row did. */
    internal fun groupByAgent(
        rows: List<ShellConversationRowModel>,
        summaries: List<ChatConversationSummary>,
    ): List<ConversationsPageGroup> {
        val byId = summaries.associateBy { it.id }
        return rows
            .groupBy { byId[it.id]?.agentId.orEmpty() }
            .map { (agentId, agentRows) ->
                ConversationsPageGroup(
                    agentId = agentId,
                    agentName = byId[agentRows.first().id]?.agentName?.takeIf { it.isNotBlank() } ?: agentId,
                    rows = agentRows,
                )
            }
    }
}
