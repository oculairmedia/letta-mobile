package com.letta.mobile.ui.chat.surface

import androidx.compose.runtime.saveable.SaverScope
import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import com.letta.mobile.ui.chat.surface.recents.RecentInteractionsMapping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** letta-mobile-y5q9z: the canvas bubble's three views and how they move between each other. */
class CanvasBubbleStateTest {
    @Test
    fun startsCollapsedAndTheHeadTogglesTheCard() {
        val state = CanvasBubbleState()
        assertFalse(state.expanded)
        state.toggleExpanded()
        assertTrue(state.expanded)
        state.toggleExpanded()
        assertFalse(state.expanded)
    }

    @Test
    fun thePlusOpensTheRecentsOnAnOpenCardAndClosesThem() {
        val state = CanvasBubbleState()
        state.toggleRecents()
        assertTrue(state.expanded, "the recents open on the card")
        assertTrue(state.recentsOpen)
        state.toggleRecents()
        assertTrue(state.expanded, "closing the recents leaves the card open")
        assertFalse(state.recentsOpen)
    }

    @Test
    fun collapsingClosesTheRecentsWithTheCard() {
        val state = CanvasBubbleState(expanded = true, recentsOpen = true)
        state.collapse()
        assertFalse(state.expanded)
        assertFalse(state.recentsOpen)
        state.expand()
        assertFalse(state.recentsOpen, "the card comes back on the exchange")
    }

    @Test
    fun backClosesTheRecentsFirstThenTheCard() {
        val state = CanvasBubbleState(expanded = true, recentsOpen = true)
        state.back()
        assertTrue(state.expanded)
        assertFalse(state.recentsOpen)
        state.back()
        assertFalse(state.expanded)
    }

    @Test
    fun aCollapsedStateNeverHasItsRecentsOpen() {
        assertFalse(CanvasBubbleState(expanded = false, recentsOpen = true).recentsOpen)
    }

    @Test
    fun aHopLandingClosesTheRecentsButKeepsTheCardOpen() {
        val state = CanvasBubbleState()
        state.onConversation("conv-1")
        state.toggleRecents()
        state.onConversation("conv-1")
        assertTrue(state.recentsOpen, "the same conversation again is no hop")
        state.onConversation("conv-2")
        assertTrue(state.expanded)
        assertFalse(state.recentsOpen)
    }

    @Test
    fun theFirstSightingOfAConversationKeepsARestoredCardAsItWas() {
        val state = CanvasBubbleState(expanded = true, recentsOpen = true)
        state.onConversation(null)
        state.onConversation("conv-1")
        assertTrue(state.recentsOpen)
    }

    @Test
    fun survivesProcessDeath() {
        val state = CanvasBubbleState(expanded = true, recentsOpen = true, conversationId = "conv:1")
        val saved = with(CanvasBubbleState.Saver) { SaverScope { true }.save(state) }
        val restored = CanvasBubbleState.Saver.restore(requireNotNull(saved))
        assertEquals(true, restored?.expanded)
        assertEquals(true, restored?.recentsOpen)
        assertEquals("conv:1", restored?.conversationId)
    }

    @Test
    fun aHopsArrivalIsTakenOnce() {
        CanvasBubbleArrival.take()
        assertFalse(CanvasBubbleArrival.take())
        CanvasBubbleArrival.mark()
        assertTrue(CanvasBubbleArrival.take())
        assertFalse(CanvasBubbleArrival.take())
    }

    @Test
    fun theRecentsListTheAgentsConversationsArchivedOnesIncluded() {
        val rows = RecentInteractionsMapping.rows(
            conversations = listOf(
                summary("conv-1", "Kitchen plan"),
                summary("conv-2", "Old sketch", archived = true),
                summary("conv-1", "Kitchen plan"),
                summary("conv-3", "Pinned idea"),
            ),
            openConversationId = "conv-1",
            pinnedIds = setOf("conv-3"),
            timeLabel = { "2h" },
        )
        assertEquals(listOf("conv-3", "conv-1", "conv-2"), rows.map { it.id }, "pinned first, listed once each")
        assertTrue(rows.single { it.id == "conv-1" }.selected)
        assertTrue(rows.single { it.id == "conv-2" }.archived)
        assertEquals("2h", rows.first().timeLabel)
    }

    private fun summary(id: String, title: String, archived: Boolean = false) = ChatConversationSummary(
        id = id,
        title = title,
        agentName = "Meridian",
        updatedAtLabel = "2026-10-09T10:00:00Z",
        lastMessagePreview = "",
        archived = archived,
    )
}
