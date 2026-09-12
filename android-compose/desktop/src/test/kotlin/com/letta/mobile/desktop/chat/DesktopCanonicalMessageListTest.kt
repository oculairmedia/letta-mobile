package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.data.timeline.TimelineMessageId
import com.letta.mobile.ui.common.GroupPosition
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopCanonicalMessageListTest {

    @Test fun theFirstRowOfADayCarriesTheDivider() {
        assertEquals(
            LocalDate.parse("2026-09-12"),
            canonicalBoundaryDate(row("a", "2026-09-12T09:00:00Z"), row("b", "2026-09-11T23:50:00Z")),
        )
    }

    @Test fun aRowSharingItsPredecessorsDayCarriesNoDivider() {
        assertNull(canonicalBoundaryDate(row("a", "2026-09-12T09:00:00Z"), row("b", "2026-09-12T01:00:00Z")))
    }

    /** The oldest resident row begins a day as far as this list can see, so it keeps its divider. */
    @Test fun theOldestResidentRowStillCarriesItsDivider() {
        assertEquals(
            LocalDate.parse("2026-09-12"),
            canonicalBoundaryDate(row("a", "2026-09-12T09:00:00Z"), older = null),
        )
    }

    @Test fun anUnparseableTimestampYieldsNoDivider() {
        assertNull(canonicalBoundaryDate(row("a", ""), older = null))
        assertNull(canonicalBoundaryDate(row("a", "not-a-date"), older = null))
    }

    @Test fun settledRowsAreIndexedBehindTheLiveOverlay() {
        val live = listOf(row("live-0", "2026-09-12T10:00:00Z"), row("live-1", "2026-09-12T09:59:00Z"))
        val settled = listOf(settledRow("s-0"), settledRow("s-1"))
        assertEquals(2, canonicalRowIndex(live, settled, "s-0"))
        assertEquals(3, canonicalRowIndex(live, settled, "s-1"))
    }

    @Test fun aLiveRowIsFoundAtItsOwnIndex() {
        val live = listOf(row("live-0", "2026-09-12T10:00:00Z"), row("live-1", "2026-09-12T09:59:00Z"))
        assertEquals(1, canonicalRowIndex(live, settled = emptyList(), identity = "live-1"))
    }

    /** An anchor that has not been paged in has no index; the caller must fall back to the tail. */
    @Test fun anAbsentAnchorHasNoIndex() {
        assertNull(canonicalRowIndex(listOf(row("live-0", "2026-09-12T10:00:00Z")), listOf(settledRow("s-0")), "gone"))
    }

    private fun row(id: String, timestamp: String): ChatRenderItem =
        ChatRenderItem.Single(
            UiMessage(id = id, role = "assistant", content = "body", timestamp = timestamp),
            GroupPosition.None,
        )

    private fun settledRow(id: String): CanonicalTimelinePresentation.Row =
        CanonicalTimelinePresentation.Row(
            identity = TimelineMessageId(id),
            revision = 1L,
            item = row(id, "2026-09-12T08:00:00Z"),
        )
}
