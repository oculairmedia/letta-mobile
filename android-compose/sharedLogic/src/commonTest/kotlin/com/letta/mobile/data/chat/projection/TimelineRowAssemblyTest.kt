package com.letta.mobile.data.chat.projection

import com.letta.mobile.util.numberOrNull

import com.letta.mobile.util.textOrNull

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.data.timeline.TimelineMessageId
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.util.Telemetry
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-qygvv.24: the live rows are hidden against the snapshot the list keys by. */
class TimelineRowAssemblyTest {
    private var ownedTelemetryHistory: AutoCloseable? = null

    @kotlin.test.BeforeTest
    fun installOwnedTelemetryHistory() {
        ownedTelemetryHistory = com.letta.mobile.util.Telemetry.installRecorder(com.letta.mobile.util.TelemetryRecorder())
    }

    @kotlin.test.AfterTest
    fun detachOwnedTelemetryHistory() {
        ownedTelemetryHistory?.close()
    }

    private fun row(key: String) = ChatRenderItem.Single(
        UiMessage(key, "assistant", "", timestamp = ""), GroupPosition.None, keyOverride = key,
    )

    private val live = listOf(row("run-local-run-65"), row("msg-cm-android-a3789bb3"))

    @Test
    fun settledRowTakesTheLiveRowsSlotInOneRead() {
        val settled = listOf("run-local-run-65", "msg-cm-android-a3789bb3", "segment-older")
        val rows = TimelineRowAssembly.assemble(live, TimelineRowAssembly.Settled(settled))
        assertEquals(emptyList(), rows.live)
        assertEquals(emptyMap(), rows.duplicates)
        assertEquals(settled, (0 until rows.size).map(rows::key))
    }

    @Test
    fun liveRowsStayAheadUntilTheirTwinIsResident() {
        val rows = TimelineRowAssembly.assemble(live, TimelineRowAssembly.Settled(listOf("segment-older", null)))
        assertEquals(2, rows.liveCount)
        assertEquals(
            listOf("run-local-run-65", "msg-cm-android-a3789bb3", "segment-older", null),
            (0 until rows.size).map(rows::key),
        )
    }

    private fun run(key: String, vararg ids: String) = ChatRenderItem.RunBlock(
        "r", ids.map { UiMessage(it, "assistant", it, timestamp = "") to GroupPosition.None }, keyOverride = key,
    )

    private fun settledRow(item: ChatRenderItem, vararg names: String) = CanonicalTimelinePresentation.Row(
        TimelineMessageId(names.first()), 1, item, liveNames = names.toSet(),
    )

    private fun drawn(rows: TimelineRowAssembly, settled: List<CanonicalTimelinePresentation.Row>) =
        rows.live.flatMap { it.messagesInOrder() }.map { it.id } + settled.flatMap { it.item.messagesInOrder() }.map { it.id }

    @Test
    fun aLiveRowKeepsItsKeyBesideTheSettledRowThatHoldsOnlyPartOfIt() {
        // A mid-turn catch-up settled a and b under the key the live run shows; c is not durable yet.
        // The live row is the one still streaming: it keeps its key (and its composition).
        val live = listOf(run("run-r-from-a", "a", "b", "c"))
        val settled = listOf(settledRow(run("run-r-from-a", "a", "b"), "a", "b"))
        val rows = CanonicalTimelinePresentation.assemble(live, settled)
        assertEquals(emptyMap(), rows.duplicates)
        assertEquals(listOf("run-r-from-a", "run-r-from-a~part"), (0 until rows.size).map(rows::key))
        assertEquals(listOf("c", "a", "b"), drawn(rows, settled), "every record drawn once")
    }

    @Test
    fun theWholeSettledRowTakesTheKeyOnceTheRestIsResident() {
        val live = listOf(run("run-r-from-a", "a", "b", "c"))
        val settled = listOf(settledRow(run("run-r-from-a", "a", "b", "c"), "a", "b", "c"))
        val rows = CanonicalTimelinePresentation.assemble(live, settled)
        assertEquals(listOf("run-r-from-a"), (0 until rows.size).map(rows::key))
    }

    @Test
    fun aLiveRowWhoseRecordsASettledRowAlreadyDrawsIsHiddenWhateverItsKey() {
        // The settled row was drawn first (a reconnect catch-up) and keeps its key; the live frame came later.
        val live = listOf(run("run-live-from-b", "b", "c"))
        val settled = listOf(settledRow(run("run-r-from-a", "a", "b", "c"), "a", "b", "c"))
        val rows = CanonicalTimelinePresentation.assemble(live, settled)
        assertEquals(emptyList(), rows.live)
        assertEquals(listOf("a", "b", "c"), drawn(rows, settled), "every record drawn once")
    }

    @Test
    fun guardNamesTheSourceItDropped() {
        TimelineRowKeyGuard.duplicateRows(listOf("guard-probe-key", "guard-probe-key")) { if (it == 0) "live" else "settled" }
        val event = Telemetry.snapshot().last {
            it.name == "timeline.duplicateKeyDropped" && it.attrs["key"]?.textOrNull == "guard-probe-key"
        }
        assertEquals("live", event.attrs["kept"]?.textOrNull)
        assertEquals("settled", event.attrs["dropped"]?.textOrNull)
        assertEquals(1, event.attrs["index"]?.numberOrNull)
    }
}
