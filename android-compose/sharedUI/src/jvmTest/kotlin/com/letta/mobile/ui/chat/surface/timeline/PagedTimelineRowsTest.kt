package com.letta.mobile.ui.chat.surface.timeline

import androidx.paging.ItemSnapshotList
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.TimelineRowAssembly
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.data.timeline.TimelineMessageId
import com.letta.mobile.ui.common.GroupPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Cached lazy providers must keep count, key, type, content and neighbours in one generation. */
class PagedTimelineRowsTest {
    @Test
    fun fiveToFourKeepsCachedSlotAndItsNeighbourInTheOriginalSnapshot() {
        val original = List(5) { row("old-$it") }
        val cached = rows(ItemSnapshotList(0, 0, original))
        val replacement = rows(ItemSnapshotList(0, 0, List(4) { row("new-$it") }))

        assertEquals(5, cached.size)
        assertEquals(original[4].item.key, cached.key(4))
        assertEquals("Single", cached.contentType(4))
        assertSame(original[4], cached.settledRowAt(4))
        assertSame(original[4].item, cached.itemAt(4))
        assertSame(original[4].item, cached.itemAt(3 + 1))
        assertNull(cached.itemAt(5))
        assertEquals(4, cached.indexOf("old-4"))
        assertEquals(4, replacement.size)
        assertNull(replacement.itemAt(4))
        assertNull(replacement.indexOf("old-4"))
    }

    @Test
    fun sameSizeReplacementDoesNotRenderANewTypeUnderACachedKey() {
        val old = row("old")
        val new = row("new", run = true)
        val cached = rows(ItemSnapshotList(0, 0, listOf(old)))
        val replacement = rows(ItemSnapshotList(0, 0, listOf(new)))

        assertEquals(cached.size, replacement.size)
        assertEquals(old.item.key, cached.key(0))
        assertEquals("Single", cached.contentType(0))
        assertSame(old.item, cached.itemAt(0))
        assertEquals(new.item.key, replacement.key(0))
        assertEquals("RunBlock", replacement.contentType(0))
        assertSame(new.item, replacement.itemAt(0))
    }

    @Test
    fun oneToZeroAndPlaceholderReplacementKeepTheirOwnSlotContracts() {
        val only = row("only")
        val cached = rows(ItemSnapshotList(0, 0, listOf(only)))
        val empty = rows(ItemSnapshotList(0, 0, emptyList()))
        val loading = rows(ItemSnapshotList(0, 1, emptyList()))

        assertEquals(only.item.key, cached.key(0))
        assertSame(only.item, cached.itemAt(0))
        assertEquals(0, empty.size)
        assertNull(empty.itemAt(0))
        assertEquals(1, loading.size)
        assertEquals("settled-slot-0", loading.key(0))
        assertNull(loading.contentType(0))
        assertNull(loading.itemAt(0))
        assertNull(loading.indexOf("only"))
    }

    @Test
    fun liveHandoverAndPlaceholderOffsetsUseTheSameCapturedAssembly() {
        val resident = row("settled")
        val live = row("live").item
        val captured = rows(
            snapshot = ItemSnapshotList(1, 1, listOf(resident)),
            live = listOf(live, resident.item),
            leading = 1,
        )

        assertEquals(1, captured.liveCount)
        assertEquals(5, captured.size)
        assertEquals(live.key, captured.key(1))
        assertSame(live, captured.itemAt(1))
        assertNull(captured.settledRowAt(1))
        assertEquals("settled-slot-1", captured.key(2))
        assertNull(captured.itemAt(2))
        assertNull(captured.contentType(2))
        assertSame(resident.item, captured.itemAt(3))
        assertEquals(resident.item.key, captured.key(3))
        assertEquals("Single", captured.contentType(3))
        assertNull(captured.itemAt(4))
        assertEquals(1, captured.indexOf("live"))
        assertEquals(3, captured.indexOf("settled"))
    }

    private fun rows(
        snapshot: ItemSnapshotList<CanonicalTimelinePresentation.Row>,
        live: List<ChatRenderItem> = emptyList(),
        leading: Int = 0,
    ) = PagedRows(
        leading = leading,
        assembly = TimelineRowAssembly.assemble(live, snapshot.map { it?.item?.key }),
        identity = live to snapshot,
    )

    private fun row(id: String, run: Boolean = false): CanonicalTimelinePresentation.Row {
        val message = UiMessage(id = id, role = "assistant", content = id, timestamp = "2026-10-26T10:00:00Z")
        val item = if (run) {
            ChatRenderItem.RunBlock(runId = id, messages = listOf(message to GroupPosition.None))
        } else {
            ChatRenderItem.Single(message, GroupPosition.None)
        }
        return CanonicalTimelinePresentation.Row(identity = TimelineMessageId(id), revision = 1, item = item)
    }
}
