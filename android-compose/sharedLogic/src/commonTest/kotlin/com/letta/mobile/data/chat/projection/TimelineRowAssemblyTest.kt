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
    fun cancellationReplayDistinguishesSharedRunIdentityFromRepeatedRecords() {
        // S3-2 order: error, reasoning block, second key, settlement.
        // Membership is synthetic: the capture has keys/ids but no block members.
        val error = row("cancel-error").copy(message = UiMessage(
            "cancel-error", "error", "Previous response cancelled.", timestamp = "", runId = "r",
        ))
        val reasoning = run("run-r-from-reasoning", "reasoning")
        val reply = run("run-r-from-reply", "reply")
        val repeated = run("run-r-from-other-key", "reply")
        Telemetry.renderDiagEnabled.set(true)
        try {
            val snapshots = listOf(
                listOf(error, reasoning),
                listOf(error, reasoning, reply),
                listOf(error, reasoning, reply, repeated),
            )
            val expected = listOf(0 to 0, 1 to 0, 1 to 1)
            snapshots.zip(expected).forEach { (live, counts) ->
                val rows = TimelineRowAssembly.assemble(live, TimelineRowAssembly.Settled(emptyList()))
                assertEquals(live, rows.live, "diagnostics preserve every row")
                assertEquals(emptyMap(), rows.duplicates, "different keys do not trip the key guard")
                val event = Telemetry.snapshot().firstOrNull { it.name == "timeline.liveRunMultiplicity" }
                assertEquals(counts.first, event?.attrs?.get("repeatedRunGroups")?.numberOrNull)
                assertEquals(counts.second, event?.attrs?.get("repeatedRecords")?.numberOrNull)
                assertEquals(setOf("liveRuns", "repeatedRunGroups", "repeatedRecords"), event?.attrs?.keys)
            }
            val handedOff = TimelineRowAssembly.assemble(
                listOf(error, reasoning, reply), TimelineRowAssembly.Settled(listOf(reply.key)),
            )
            assertEquals(listOf(error, reasoning), handedOff.live)
            val event = Telemetry.snapshot().first { it.name == "timeline.liveRunMultiplicity" }
            assertEquals(0, event.attrs["repeatedRunGroups"]?.numberOrNull)
        } finally {
            Telemetry.renderDiagEnabled.set(false)
        }
    }

    @Test
    fun runMultiplicityDiagnosticsAreOptInAndEmptySnapshotsClearCounts() {
        val repeated = listOf(run("first", "reply"), run("second", "reply"))
        Telemetry.renderDiagEnabled.set(false)
        TimelineRowAssembly.assemble(repeated, TimelineRowAssembly.Settled(emptyList()))
        assertEquals(emptyList(), Telemetry.snapshot().filter { it.name == "timeline.liveRunMultiplicity" })
        Telemetry.renderDiagEnabled.set(true)
        try {
            TimelineRowAssembly.assemble(repeated, TimelineRowAssembly.Settled(emptyList()))
            TimelineRowAssembly.assemble(emptyList(), TimelineRowAssembly.Settled(emptyList()))
            val events = Telemetry.snapshot().filter { it.name == "timeline.liveRunMultiplicity" }.asReversed()
            assertEquals(2, events.size, "one bounded event per assembly")
            assertEquals(1, events.first().attrs["repeatedRecords"]?.numberOrNull)
            assertEquals(0, events.last().attrs["liveRuns"]?.numberOrNull)
            assertEquals(0, events.last().attrs["repeatedRunGroups"]?.numberOrNull)
            assertEquals(0, events.last().attrs["repeatedRecords"]?.numberOrNull)
        } finally {
            Telemetry.renderDiagEnabled.set(false)
        }
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
