package com.letta.mobile.ui.canvas

import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.canvas.CanvasStorageFault
import com.letta.mobile.data.canvas.affecting
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasStorageFaultBannerTest {
    private fun fault(kind: CanvasStorageFault.Kind, canvas: String?) = CanvasStorageFault(
        kind = kind,
        documentId = "1d0c78f4",
        canvasId = canvas?.let(::CanvasId),
        documentBytes = 62_600_352,
        message = "put failed",
        atEpochMs = 13 * 3_600_000L + 7 * 60_000L,
    )

    @Test
    fun aFailedSaveSaysWhatIsAtRiskAndSinceWhen() {
        val text = canvasStorageFaultText(fault(CanvasStorageFault.Kind.SAVE_FAILED, "board"), TimeZone.UTC)
        assertEquals("This board couldn't be saved; changes since 13:07 are at risk", text.headline)
        assertEquals("Notebook 1d0c78f4 · 59.7 MB · put failed", text.detail)
    }

    @Test
    fun aBoardShowsItsOwnFaultsAndUnattributedOnesButNotBudgetNotes() {
        val faults = listOf(
            fault(CanvasStorageFault.Kind.SAVE_FAILED, "board"),
            fault(CanvasStorageFault.Kind.SAVE_FAILED, "other"),
            fault(CanvasStorageFault.Kind.QUARANTINED, null),
            fault(CanvasStorageFault.Kind.NEAR_BUDGET, "board"),
        )
        assertEquals(listOf(faults[0], faults[2]), faults.affecting(CanvasId("board")))
    }
}
