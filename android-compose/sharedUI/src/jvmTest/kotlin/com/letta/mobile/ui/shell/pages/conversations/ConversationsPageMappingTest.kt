package com.letta.mobile.ui.shell.pages.conversations

import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import com.letta.mobile.ui.shell.sidebar.ShellArchiveFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** letta-mobile-ckmub.2.4: the fleet conversations page's grouping, filter and marks. */
class ConversationsPageMappingTest {
    private val now = Instant.parse("2026-10-12T12:00:00Z")

    private fun summary(id: String, agentId: String, archived: Boolean = false) = ChatConversationSummary(
        id = id,
        title = "Title $id",
        agentName = "Name $agentId",
        updatedAtLabel = "2026-10-12T11:00:00Z",
        lastMessagePreview = "",
        agentId = agentId,
        archived = archived,
    )

    private val conversations = listOf(
        summary("c1", "b"),
        summary("c2", "a"),
        summary("c3", "b"),
        summary("c4", "a", archived = true),
    )

    @Test
    fun pinnedLeadThenOneGroupPerAgentInFirstAppearanceOrder() {
        val state = ConversationsPageMapping.state(
            ConversationsPageInput(conversations = conversations, pinnedIds = setOf("c3"), workingIds = setOf("c2")),
            now,
        )
        assertEquals(listOf("c3"), state.pinned.map { it.id })
        assertEquals(listOf("b", "a"), state.groups.map { it.agentId })
        assertEquals(listOf("Name b", "Name a"), state.groups.map { it.agentName })
        assertEquals(listOf(listOf("c1"), listOf("c2")), state.groups.map { group -> group.rows.map { it.id } })
        assertTrue(state.groups[1].rows.single().thinking)
        assertEquals("1h", state.pinned.single().timeLabel)
    }

    @Test
    fun theFilterPicksRowsAndTheArchivedDialogSeesEveryArchivedChat() {
        val archived = ConversationsPageMapping.state(ConversationsPageInput(conversations, ShellArchiveFilter.Archived), now)
        assertEquals(listOf("c4"), archived.rows.map { it.id })
        val active = ConversationsPageMapping.state(ConversationsPageInput(conversations), now)
        assertEquals(listOf("c4"), active.archivedConversations.map { it.id })
        assertEquals(3, active.rows.size)
    }

    @Test
    fun anErrorShowsOnlyWhenNothingIsCached() {
        assertNull(ConversationsPageMapping.state(ConversationsPageInput(conversations, error = "offline"), now).error)
        assertEquals("offline", ConversationsPageMapping.state(ConversationsPageInput(error = "offline"), now).error)
    }

    @Test
    fun sectionsAreThePinnedThenEachAgent() {
        val state = ConversationsPageMapping.state(ConversationsPageInput(conversations, pinnedIds = setOf("c1")), now)
        assertEquals(listOf("pinned", "agent-a", "agent-b"), conversationSections(state).map { it.key })
    }
}
