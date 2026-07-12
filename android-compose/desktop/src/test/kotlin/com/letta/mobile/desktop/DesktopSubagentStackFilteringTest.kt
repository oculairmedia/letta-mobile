package com.letta.mobile.desktop

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.desktop.chat.ConversationArchiveFilter
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-5172y.2: the desktop conversation rail must group ephemeral
 * "Letta Code" subagent conversations by AUTHORITATIVE PARENT PROVENANCE (via
 * the shared [com.letta.mobile.data.chat.runtime.groupSubagentConversations]
 * model), NOT by display name. These tests pin the fixed defect: unrelated
 * agents that merely share the display name "Letta Code" must NOT merge into a
 * single desktop stack, same-provenance children DO collapse, and normal
 * (non-subagent) agents are unaffected.
 */
class DesktopSubagentStackFilteringTest {

    private fun conversation(
        id: String,
        agentId: String,
        agentName: String,
        archived: Boolean = false,
        updatedAtLabel: String = "Just now",
    ): DesktopConversationSummary = DesktopConversationSummary(
        id = id,
        title = "Conversation $id",
        agentName = agentName,
        updatedAtLabel = updatedAtLabel,
        lastMessagePreview = "preview",
        agentId = agentId,
        archived = archived,
    )

    private fun subagentEntry(
        subagentConversationId: String,
        subagentAgentId: String,
        parentConversationId: String?,
        status: String = SubagentStatus.RUNNING,
    ): SubagentEntry = SubagentEntry(
        toolCallId = "tc-$subagentConversationId",
        subagentType = "Letta Code",
        status = status,
        subagentAgentId = subagentAgentId,
        subagentConversationId = subagentConversationId,
        parentConversationId = parentConversationId,
    )

    @Test
    fun sameNameDifferentParentSubagentsDoNotMergeIntoOneStack() {
        // Two "Letta Code" conversations that SHARE the display name but come
        // from DIFFERENT parents. Selecting one must surface only its own
        // provenance sibling(s), never the unrelated same-name conversation.
        val convA = conversation(id = "conv-a", agentId = "sub-a", agentName = "Letta Code")
        val convB = conversation(id = "conv-b", agentId = "sub-b", agentName = "Letta Code")
        val conversations = listOf(convA, convB)
        val activeSubagents = listOf(
            subagentEntry(
                subagentConversationId = "conv-a",
                subagentAgentId = "sub-a",
                parentConversationId = "parent-1",
            ),
            subagentEntry(
                subagentConversationId = "conv-b",
                subagentAgentId = "sub-b",
                parentConversationId = "parent-2",
            ),
        )

        val selectingA = filterStackConversations(
            conversations = conversations,
            activeSubagents = activeSubagents,
            selectedAgentName = "Letta Code",
            selectedConversationId = "conv-a",
            archiveFilter = ConversationArchiveFilter.All,
        )

        assertEquals(
            listOf("conv-a"),
            selectingA.map { it.id },
            "same-name/different-parent conversations must NOT merge into one stack",
        )
        assertTrue(
            selectingA.none { it.id == "conv-b" },
            "the unrelated parent-2 conversation must not leak into parent-1's stack",
        )
    }

    @Test
    fun sameProvenanceSubagentChildrenCollapseIntoOneStack() {
        // Three "Letta Code" conversations sharing ONE parent collapse together,
        // regardless of which sibling is selected.
        val conversations = listOf(
            conversation(id = "conv-1", agentId = "sub-1", agentName = "Letta Code", updatedAtLabel = "3 min"),
            conversation(id = "conv-2", agentId = "sub-2", agentName = "Letta Code", updatedAtLabel = "1 min"),
            conversation(id = "conv-3", agentId = "sub-3", agentName = "Letta Code", updatedAtLabel = "2 min"),
        )
        val activeSubagents = listOf(
            subagentEntry("conv-1", "sub-1", parentConversationId = "parent-shared"),
            subagentEntry("conv-2", "sub-2", parentConversationId = "parent-shared"),
            subagentEntry("conv-3", "sub-3", parentConversationId = "parent-shared"),
        )

        val result = filterStackConversations(
            conversations = conversations,
            activeSubagents = activeSubagents,
            selectedAgentName = "Letta Code",
            selectedConversationId = "conv-2",
            archiveFilter = ConversationArchiveFilter.All,
        )

        assertEquals(
            setOf("conv-1", "conv-2", "conv-3"),
            result.map { it.id }.toSet(),
            "same-provenance children must collapse into one stack",
        )
    }

    @Test
    fun normalAgentConversationsUnaffectedByProvenanceGrouping() {
        // A normal agent (no subagent entry) keeps its historical display-name
        // membership over the shared model's `ungrouped` list.
        val conversations = listOf(
            conversation(id = "n1", agentId = "agent-ada", agentName = "Ada", updatedAtLabel = "5 min"),
            conversation(id = "n2", agentId = "agent-ada", agentName = "Ada", updatedAtLabel = "1 min"),
            conversation(id = "other", agentId = "agent-ops", agentName = "Ops"),
        )

        val result = filterStackConversations(
            conversations = conversations,
            activeSubagents = emptyList(),
            selectedAgentName = "Ada",
            selectedConversationId = "n1",
            archiveFilter = ConversationArchiveFilter.All,
        )

        assertEquals(
            listOf("n1", "n2"),
            result.map { it.id },
            "normal agents retain display-name membership, newest first",
        )
        assertTrue(result.none { it.id == "other" }, "a different normal agent must not leak in")
    }

    @Test
    fun normalAgentSharingNameWithoutSubagentEntryStaysNameGrouped() {
        // Defensive: two agents named "Ada" with NO subagent entries fall to the
        // ungrouped/name path and remain merged (existing behavior preserved).
        val conversations = listOf(
            conversation(id = "a1", agentId = "agent-ada-1", agentName = "Ada"),
            conversation(id = "a2", agentId = "agent-ada-2", agentName = "Ada"),
        )

        val result = filterStackConversations(
            conversations = conversations,
            activeSubagents = emptyList(),
            selectedAgentName = "Ada",
            selectedConversationId = "a1",
            archiveFilter = ConversationArchiveFilter.All,
        )

        assertEquals(
            setOf("a1", "a2"),
            result.map { it.id }.toSet(),
            "existing name-grouping for non-subagent agents is preserved",
        )
    }

    @Test
    fun archiveFilterAppliedAfterProvenanceGrouping() {
        val conversations = listOf(
            conversation(id = "live", agentId = "sub-1", agentName = "Letta Code", archived = false),
            conversation(id = "gone", agentId = "sub-2", agentName = "Letta Code", archived = true),
        )
        val activeSubagents = listOf(
            subagentEntry("live", "sub-1", parentConversationId = "parent-shared"),
            subagentEntry("gone", "sub-2", parentConversationId = "parent-shared"),
        )

        val activeOnly = filterStackConversations(
            conversations = conversations,
            activeSubagents = activeSubagents,
            selectedAgentName = "Letta Code",
            selectedConversationId = "live",
            archiveFilter = ConversationArchiveFilter.Active,
        )
        val archivedOnly = filterStackConversations(
            conversations = conversations,
            activeSubagents = activeSubagents,
            selectedAgentName = "Letta Code",
            selectedConversationId = "live",
            archiveFilter = ConversationArchiveFilter.Archived,
        )

        assertEquals(listOf("live"), activeOnly.map { it.id }, "Active filter drops archived members")
        assertEquals(listOf("gone"), archivedOnly.map { it.id }, "Archived filter keeps only archived members")
    }
}
