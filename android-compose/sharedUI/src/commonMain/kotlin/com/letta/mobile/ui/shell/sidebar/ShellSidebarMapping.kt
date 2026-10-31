package com.letta.mobile.ui.shell.sidebar

import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.data.chat.runtime.ConversationListGroups
import com.letta.mobile.data.chat.runtime.ConversationListOrder
import com.letta.mobile.data.chat.runtime.displayTitle
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.util.time.TimeLabels
import kotlin.time.Instant

/** Which conversations a host marks: the open one, the one whose agent is working, those being deleted. */
data class ShellConversationMarks(
    val selectedId: String? = null,
    val thinkingId: String? = null,
    val deletingIds: Set<String> = emptySet(),
    /** Pinned conversations, listed first. */
    val pinnedIds: Set<String> = emptySet(),
    /**  (G37): each fork's source conversation id (ConversationLineage), for "Forked from …". */
    val parentIds: Map<String, String> = emptyMap(),
    /** Settled conversations, listed after the active ones. */
    val settledIds: Set<String> = emptySet(),
    /** Each marked conversation's emoji. */
    val markers: Map<String, String> = emptyMap(),
)

/**
 * What a host knows about its sidebar, in platform-neutral terms. The host
 * has already scoped [conversations] to the agent and the filter, and [canvases] to the filter;
 * [archivedConversations] are every archived chat (the Archived chats dialog).
 */
data class ShellSidebarInput(
    val agent: ShellPanelAgent,
    val mode: WorkPlayMode = WorkPlayMode.Work,
    val home: Boolean = false,
    val selectedSection: LensDestination? = null,
    val settingsSelected: Boolean = false,
    val archiveFilter: ShellArchiveFilter = ShellArchiveFilter.Active,
    val conversations: List<ChatConversationSummary> = emptyList(),
    val archivedConversations: List<ChatConversationSummary> = emptyList(),
    val marks: ShellConversationMarks = ShellConversationMarks(),
    val chatOptions: ShellChatOptionsState? = null,
    val canvases: List<CanvasDocument> = emptyList(),
    val activeCanvasId: CanvasId? = null,
    val archivedCanvasIds: Set<CanvasId> = emptySet(),
)

/** Pure mapping from the platform-neutral conversation summaries to the panel's rows. */
object ShellSidebarMapping {
    /** The agent panel a host's [input] draws, its times relative to [now]. */
    fun panelState(input: ShellSidebarInput, now: Instant): ShellAgentPanelState {
        val timeLabel = { raw: String -> TimeLabels.ageLabel(raw, now) }
        return ShellAgentPanelState(
            agent = input.agent,
            mode = input.mode,
            home = input.home,
            selectedSection = input.selectedSection,
            settingsSelected = input.settingsSelected,
            archiveFilter = input.archiveFilter,
            conversations = conversationRows(input.conversations, input.marks, timeLabel),
            archivedConversations = conversationRows(input.archivedConversations, input.marks, timeLabel),
            chatOptions = input.chatOptions,
            canvases = input.canvases.map { canvas ->
                ShellCanvasRowModel(
                    id = canvas.id,
                    title = canvas.title,
                    timeLabel = TimeLabels.age(Instant.fromEpochMilliseconds(canvas.updatedAtEpochMs), now),
                    selected = canvas.id == input.activeCanvasId,
                    archived = canvas.id in input.archivedCanvasIds,
                )
            },
        )
    }

    /** The placeholder preview a conversation carries before its messages load; never shown. */
    private const val LOADING_PREVIEW = "Loaded from backend"

    /**
     * [conversations] as panel rows, the pinned ones first and the settled ones last (each group
     * keeps its order).
     * [timeLabel] turns a summary's `updatedAtLabel` into the row's trailing time (see
     * [TimeLabels.ageLabel]).
     */
    fun conversationRows(
        conversations: List<ChatConversationSummary>,
        marks: ShellConversationMarks,
        timeLabel: (String) -> String,
    ): List<ShellConversationRowModel> {
        val titles = conversations.associate { it.id to it.displayTitle() }
        val ordered = ConversationListOrder.arrange(conversations, ConversationListGroups(marks.pinnedIds, marks.settledIds)) { it.id }
        return ordered.map { conversation ->
            ShellConversationRowModel(
                id = conversation.id,
                title = conversation.displayTitle(),
                preview = cleanPreview(conversation.lastMessagePreview),
                timeLabel = timeLabel(conversation.updatedAtLabel),
                selected = conversation.id == marks.selectedId,
                thinking = conversation.id == marks.thinkingId,
                deleting = conversation.id in marks.deletingIds,
                archived = conversation.archived,
                pinned = conversation.id in marks.pinnedIds,
                settled = conversation.id in marks.settledIds,
                marker = marks.markers[conversation.id],
                forkedFrom = marks.parentIds[conversation.id]?.let { parent -> titles[parent] ?: UNLISTED_PARENT },
            )
        }
    }

    /** A fork whose source is not in the list (archived, another agent's, or gone). */
    const val UNLISTED_PARENT = "an earlier chat"

    /** The preview line under a title: trimmed, and blank for the loading placeholder. */
    fun cleanPreview(raw: String): String =
        raw.trim().takeUnless { it.equals(LOADING_PREVIEW, ignoreCase = true) }.orEmpty()
}
