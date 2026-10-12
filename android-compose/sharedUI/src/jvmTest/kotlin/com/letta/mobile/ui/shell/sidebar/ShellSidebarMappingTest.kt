package com.letta.mobile.ui.shell.sidebar

import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ShellSidebarMappingTest {
    private val now = Instant.parse("2026-10-07T12:00:00Z")

    @Test
    fun relativeTimeUsesTheCompactBuckets() {
        assertEquals("now", ShellRelativeTime.compact(now - 30.seconds, now))
        assertEquals("4m", ShellRelativeTime.compact(now - 4.minutes, now))
        assertEquals("13h", ShellRelativeTime.compact(now - 13.hours, now))
        assertEquals("2d", ShellRelativeTime.compact(now - 2.days, now))
        assertEquals("3w", ShellRelativeTime.compact(now - 21.days, now))
        assertEquals("2mo", ShellRelativeTime.compact(now - 61.days, now))
        assertEquals("now", ShellRelativeTime.compact(now + 5.minutes, now))
    }

    /** letta-mobile-ckmub.3.10: a host's sidebar input becomes the panel it draws (the desktop's binding). */
    @Test
    fun panelStateMarksTheOpenChatTheActiveCanvasAndForks() {
        val chats = listOf(
            ChatConversationSummary(id = "c1", title = "Shipping notes", agentName = "", updatedAtLabel = "2026-10-07T11:00:00Z", lastMessagePreview = "Loaded from backend"),
            ChatConversationSummary(id = "c2", title = "Fork", agentName = "", updatedAtLabel = "", lastMessagePreview = "hi"),
        )
        val state = ShellSidebarMapping.panelState(
            ShellSidebarInput(
                agent = ShellPanelAgent(name = "Meridian"),
                home = true,
                conversations = chats,
                marks = ShellConversationMarks(selectedId = "c1", thinkingId = "c1", parentIds = mapOf("c2" to "c1")),
                canvases = listOf(
                    com.letta.mobile.data.canvas.CanvasDocument(id = com.letta.mobile.data.canvas.CanvasId("a"), title = "Roadmap"),
                    com.letta.mobile.data.canvas.CanvasDocument(id = com.letta.mobile.data.canvas.CanvasId("b"), title = "Old"),
                ),
                activeCanvasId = com.letta.mobile.data.canvas.CanvasId("a"),
                archivedCanvasIds = setOf(com.letta.mobile.data.canvas.CanvasId("b")),
            ),
            now,
        )
        val (open, fork) = state.conversations
        assertEquals(true, state.home)
        assertEquals(listOf(true, true, "", "1h"), listOf(open.selected, open.thinking, open.preview, open.timeLabel))
        assertEquals("Forked from Shipping notes", fork.subtitle)
        assertEquals(listOf(true, false), state.canvases.map { it.selected })
        assertEquals(listOf(false, true), state.canvases.map { it.archived })
    }

    @Test
    fun relativeTimeParsesIsoAndPassesOtherTextThrough() {
        assertEquals("1h", ShellRelativeTime.compact("2026-10-07T11:00:00Z", now))
        assertEquals("Remote", ShellRelativeTime.compact("Remote", now))
    }

    @Test
    fun pinnedConversationsLeadAndCarryTheirPin() {
        // letta-mobile-bzvro.17
        val rows = ShellSidebarMapping.conversationRows(
            conversations = listOf(summary("a", archived = false), summary("b", archived = false), summary("c", archived = false)),
            marks = ShellConversationMarks(pinnedIds = setOf("c")),
            timeLabel = { it },
        )
        assertEquals(listOf("c", "a", "b"), rows.map { it.id })
        assertEquals(listOf(true, false, false), rows.map { it.pinned })
    }

    @Test
    fun aForkSaysWhichConversationItCameFrom() {
        // letta-mobile-7mys7 (G37)
        val rows = ShellSidebarMapping.conversationRows(
            conversations = listOf(summary("a", archived = false), summary("b", archived = false), summary("c", archived = false)),
            marks = ShellConversationMarks(parentIds = mapOf("b" to "a", "c" to "gone")),
            timeLabel = { it },
        )
        assertEquals(listOf(null, "Title a", ShellSidebarMapping.UNLISTED_PARENT), rows.map { it.forkedFrom })
        assertEquals("Forked from Title a", rows[1].subtitle)
        assertEquals(rows[0].preview, rows[0].subtitle)
    }

    @Test
    fun theRowMenuOffersForkOnlyWhereTheHostCanFork() {
        val withFork = com.letta.mobile.ui.shell.ShellConversationManageMenu(onForkRequest = {}).items().map { it.label }
        val without = com.letta.mobile.ui.shell.ShellConversationManageMenu().items().map { it.label }
        assertEquals(listOf("Fork chat…"), withFork)
        assertEquals(emptyList(), without)
    }

    @Test
    fun conversationRowsCarryTheHostsMarks() {
        val rows = ShellSidebarMapping.conversationRows(
            conversations = listOf(summary("a", archived = false), summary("b", archived = true), summary("c", archived = false)),
            marks = ShellConversationMarks(selectedId = "a", thinkingId = "b", deletingIds = setOf("c")),
            timeLabel = { "t:$it" },
        )
        assertEquals(listOf(true, false, false), rows.map { it.selected })
        assertEquals(listOf(false, true, false), rows.map { it.thinking })
        assertEquals(listOf(false, false, true), rows.map { it.deleting })
        assertEquals(listOf(false, true, false), rows.map { it.archived })
        assertEquals("t:2026-10-07T11:00:00Z", rows.first().timeLabel)
        assertEquals("Title a", rows.first().title)
    }

    @Test
    fun previewDropsTheLoadingPlaceholder() {
        assertEquals("", ShellSidebarMapping.cleanPreview("  Loaded from backend "))
        assertEquals("Hello there", ShellSidebarMapping.cleanPreview(" Hello there\n"))
    }

    @Test
    fun archiveFilterAdmitsTheRightItems() {
        assertEquals(listOf(true, false), listOf(false, true).map(ShellArchiveFilter.Active::admits))
        assertEquals(listOf(false, true), listOf(false, true).map(ShellArchiveFilter.Archived::admits))
        assertEquals(listOf(true, true), listOf(false, true).map(ShellArchiveFilter.All::admits))
    }

    private fun summary(id: String, archived: Boolean) = ChatConversationSummary(
        id = id,
        title = "Title $id",
        agentName = "Meridian",
        updatedAtLabel = "2026-10-07T11:00:00Z",
        lastMessagePreview = "Preview $id",
        archived = archived,
    )
}
