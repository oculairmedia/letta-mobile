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
