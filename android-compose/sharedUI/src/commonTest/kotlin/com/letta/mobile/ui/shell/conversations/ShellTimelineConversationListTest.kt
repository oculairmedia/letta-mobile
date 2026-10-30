package com.letta.mobile.ui.shell.conversations

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.data.chat.conversations.TimelineConversationsState
import com.letta.mobile.ui.shell.navDrawerState
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelActions
import com.letta.mobile.ui.shell.sidebar.ShellArchiveFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * letta-mobile-ckmub.17.15: the owner's conversations reach the shared drawer (the web's recent
 * conversations list): the focused agent's, under the archive filter, pinned first, forks marked.
 */
class ShellTimelineConversationListTest {
    private val now = Instant.parse("2026-10-21T12:00:00Z")
    private val roster = listOf("agent-1" to "Nora", "agent-2" to "Ada")

    @Test
    fun theFocusedAgentsConversationsListInThePanelPinnedFirst() {
        val workspace = ShellWorkspaceState(ShellDestination.Chat())
        workspace.rosterChanged(roster.map { AgentId(it.first) })
        val list = list(
            conversations = listOf(
                conversation("c1", "agent-1", "First"),
                conversation("c2", "agent-2", "Ada's"),
                conversation("c3", "agent-1", "Pinned"),
                conversation("c4", "agent-1", "Old").copy(archived = true),
            ),
            pinned = setOf("c3"),
            parents = mapOf("c3" to "c1"),
        )

        val panel = workspace.navDrawerState(roster, now, list.drawer(workspace.focusedAgentId)).panel

        assertEquals(listOf("Pinned", "First"), panel.conversations.map { it.title })
        assertEquals(listOf("Old"), panel.archivedConversations.map { it.title })
    }

    @Test
    fun withoutAnOwnerTheRowsOfferNoEdits() {
        val base = ShellAgentPanelActions()
        val actions = list(emptyList()).panelActions(base)

        assertNull(actions.onRenameConversation)
        assertEquals(base, actions)
    }

    @Test
    fun aTabIsTitledByTheConversationThenItsAgent() {
        val ada = conversation("c2", "agent-2", summary = null).copy(agentName = "Ada")
        val list = list(listOf(conversation("c1", "agent-1", "Plans"), ada))

        assertEquals("Plans", list.tab("c1")?.title)
        assertEquals("Ada", list.tab("c2")?.title)
        assertNull(list.tab("gone"))
    }

    @Test
    fun partialRecentsNeverCloseTabsOutsideTheWindow() {
        val workspace = ShellWorkspaceState(ShellDestination.Chat())
        workspace.tabs = com.letta.mobile.data.desktopshell.ConversationTabsState(listOf("outside-window", "recent"))
        val partial = TimelineConversationsState(
            conversations = listOf(conversation("recent", "agent-1", "Recent")), loaded = true,
        )
        partial.authoritativeConversationIds()?.let(workspace::conversationsReported)
        assertEquals(listOf("outside-window", "recent"), workspace.tabs.openConversationIds)
        partial.copy(authoritative = true).authoritativeConversationIds()?.let(workspace::conversationsReported)
        assertEquals(listOf("recent"), workspace.tabs.openConversationIds, "legacy full-list pruning remains intact")
    }

    private fun list(
        conversations: List<Conversation>,
        pinned: Set<String> = emptySet(),
        parents: Map<String, String> = emptyMap(),
    ) = ShellTimelineConversationList(
        state = TimelineConversationsState(conversations = conversations, loaded = true, selected = ConversationId("c1")),
        pinnedIds = pinned,
        parentIds = parents,
        archiveFilter = ShellArchiveFilter.Active,
        owner = null,
        onArchiveFilterChange = {},
    )

    private fun conversation(id: String, agent: String, summary: String?) =
        Conversation(id = ConversationId(id), agentId = AgentId(agent), summary = summary)
}
