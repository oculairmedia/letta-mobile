package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.util.Telemetry
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-qygvv.24: the live rows are hidden against the snapshot the list keys by. */
class TimelineRowAssemblyTest {
    private fun row(key: String) = ChatRenderItem.Single(
        UiMessage(key, "assistant", "", timestamp = ""), GroupPosition.None, keyOverride = key,
    )

    private val live = listOf(row("run-local-run-65"), row("msg-cm-android-a3789bb3"))

    @Test
    fun settledRowTakesTheLiveRowsSlotInOneRead() {
        val settled = listOf("run-local-run-65", "msg-cm-android-a3789bb3", "segment-older")
        val rows = TimelineRowAssembly.assemble(live, settled)
        assertEquals(emptyList(), rows.live)
        assertEquals(emptyMap(), rows.duplicates)
        assertEquals(settled, (0 until rows.size).map(rows::key))
    }

    @Test
    fun liveRowsStayAheadUntilTheirTwinIsResident() {
        val rows = TimelineRowAssembly.assemble(live, listOf("segment-older", null))
        assertEquals(2, rows.liveCount)
        assertEquals(
            listOf("run-local-run-65", "msg-cm-android-a3789bb3", "segment-older", null),
            (0 until rows.size).map(rows::key),
        )
    }

    @Test
    fun guardNamesTheSourceItDropped() {
        TimelineRowKeyGuard.duplicateRows(listOf("guard-probe-key", "guard-probe-key")) { if (it == 0) "live" else "settled" }
        val event = Telemetry.snapshot().last {
            it.name == "timeline.duplicateKeyDropped" && it.attrs["key"] == "guard-probe-key"
        }
        assertEquals("live", event.attrs["kept"])
        assertEquals("settled", event.attrs["dropped"])
        assertEquals(1, event.attrs["index"])
    }
}
