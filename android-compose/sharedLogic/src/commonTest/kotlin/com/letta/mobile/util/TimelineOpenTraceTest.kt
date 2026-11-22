package com.letta.mobile.util

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.util.TimelineOpenTrace.Stage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class TimelineOpenTraceTest : TelemetryRecordingTest() {
    private val clock = TestTimeSource()
    private val conversation = ConversationId("conv-open-trace")

    @BeforeTest
    fun enableTrace() {
        TimelineOpenTrace.clearForTest()
        TimelineOpenTrace.timeSource = clock
        Telemetry.timelineOpenTraceEnabled.set(true)
    }

    @AfterTest
    fun disableTrace() {
        Telemetry.timelineOpenTraceEnabled.set(false)
        TimelineOpenTrace.clearForTest()
    }

    private fun events(): List<Telemetry.Event> =
        Telemetry.snapshot().asReversed().filter { it.tag == TimelineOpenTrace.TAG }

    private fun Telemetry.Event.number(key: String): Long? = attrs[key]?.numberOrNull?.toLong()

    @Test
    fun everyStageFiresOnceInOrderUnderOneOpenIdOnTheFakeClock() {
        val openId = assertNotNull(TimelineOpenTrace.requested(conversation))
        clock += 10.milliseconds
        TimelineOpenTrace.mark(conversation, Stage.PresentationBound)
        clock += 5.milliseconds
        TimelineOpenTrace.mark(conversation, Stage.CacheReadStart)
        clock += 20.milliseconds
        TimelineOpenTrace.mark(conversation, Stage.CacheReadEnd, count = 64)
        clock += 7.milliseconds
        TimelineOpenTrace.mark(conversation, Stage.FirstFrameWithRows)
        clock += 3.milliseconds
        TimelineOpenTrace.mark(conversation, Stage.NetworkPageStart)
        clock += 400.milliseconds
        TimelineOpenTrace.mark(conversation, Stage.NetworkPageEnd, count = 64, bytes = 90_000L)
        clock += 30.milliseconds
        TimelineOpenTrace.mark(conversation, Stage.ReconcileEnd, count = 3)
        TimelineOpenTrace.listReplaced(conversation)
        clock += 500.milliseconds
        TimelineOpenTrace.settleIfQuiet(conversation)

        val trace = events()
        assertEquals(Stage.entries.map { it.eventName }, trace.map { it.name })
        assertEquals(setOf(openId.value), trace.map { it.number("openId") }.toSet())
        assertEquals(
            listOf(0L, 10L, 15L, 35L, 42L, 45L, 445L, 475L, 975L),
            trace.map { it.number("elapsedMs") },
        )
        assertEquals(475L, trace.single { it.name == Stage.Settled.eventName }.number("lastReplacementMs"))
        assertEquals(64L, trace.single { it.name == Stage.CacheReadEnd.eventName }.number("count"))
        val network = trace.single { it.name == Stage.NetworkPageEnd.eventName }
        assertEquals(64L, network.number("count"))
        assertEquals(90_000L, network.number("bytes"))
    }

    @Test
    fun aRepeatedStageIsNotEmittedAgain() {
        TimelineOpenTrace.requested(conversation)
        repeat(3) { TimelineOpenTrace.mark(conversation, Stage.CacheReadEnd, count = 8) }
        repeat(3) { TimelineOpenTrace.mark(conversation, Stage.FirstFrameWithRows) }

        assertEquals(
            listOf(Stage.OpenRequested, Stage.CacheReadEnd, Stage.FirstFrameWithRows).map { it.eventName },
            events().map { it.name },
        )
    }

    @Test
    fun noEventIsEmittedWhileTheTraceIsOff() {
        Telemetry.timelineOpenTraceEnabled.set(false)

        assertNull(TimelineOpenTrace.requested(conversation))
        assertNull(TimelineOpenTrace.requestedIfAbsent(conversation))
        Stage.entries.forEach { assertFalse(TimelineOpenTrace.mark(conversation, it, count = 1, bytes = 1L)) }
        TimelineOpenTrace.listReplaced(conversation)
        TimelineOpenTrace.settleIfQuiet(conversation)
        TimelineOpenTrace.FirstFrameGate(conversation).onDraw(hasRows = true)

        assertTrue(events().isEmpty())
        assertFalse(TimelineOpenTrace.tracking())
        assertFalse(TimelineOpenTrace.isActive(conversation))
    }

    @Test
    fun noEventIsEmittedWhenTelemetryItselfIsOff() {
        Telemetry.enabled.set(false)
        try {
            assertNull(TimelineOpenTrace.requested(conversation))
            assertFalse(TimelineOpenTrace.mark(conversation, Stage.CacheReadEnd, count = 1))
        } finally {
            Telemetry.enabled.set(true)
        }
        assertTrue(events().isEmpty())
    }

    @Test
    fun aStageOfAnUnopenedConversationIsIgnoredAndASwitchedOffTraceNeverOpensOne() {
        TimelineOpenTrace.requested(conversation)

        assertFalse(TimelineOpenTrace.mark(ConversationId("other"), Stage.CacheReadEnd, count = 1))

        assertEquals(listOf(Stage.OpenRequested.eventName), events().map { it.name })
    }

    @Test
    fun settledNeedsRowsDrawnAndTheHostPageBackBeforeTheQuietPeriodCounts() {
        TimelineOpenTrace.requested(conversation)
        TimelineOpenTrace.mark(conversation, Stage.CacheReadEnd, count = 5)
        clock += 900.milliseconds
        TimelineOpenTrace.settleIfQuiet(conversation)
        assertTrue(events().none { it.name == Stage.Settled.eventName }, "quiet but nothing drawn and no host page")

        TimelineOpenTrace.mark(conversation, Stage.FirstFrameWithRows)
        TimelineOpenTrace.settleIfQuiet(conversation)
        assertTrue(events().none { it.name == Stage.Settled.eventName }, "host page still out")

        TimelineOpenTrace.mark(conversation, Stage.NetworkPageStart)
        TimelineOpenTrace.mark(conversation, Stage.NetworkPageEnd, count = 0, bytes = 0L)
        TimelineOpenTrace.settleIfQuiet(conversation)
        assertEquals(1, events().count { it.name == Stage.Settled.eventName })
    }

    @Test
    fun aListReplacementRestartsTheQuietPeriodAndSettledNamesThatLastReplacement() {
        TimelineOpenTrace.requested(conversation)
        TimelineOpenTrace.mark(conversation, Stage.FirstFrameWithRows)
        TimelineOpenTrace.mark(conversation, Stage.NetworkPageStart)
        TimelineOpenTrace.mark(conversation, Stage.NetworkPageEnd, count = 2, bytes = 10L)

        clock += 300.milliseconds
        TimelineOpenTrace.listReplaced(conversation)
        clock += 499.milliseconds
        TimelineOpenTrace.settleIfQuiet(conversation)
        assertTrue(events().none { it.name == Stage.Settled.eventName }, "499 ms after the last replacement")

        clock += 1.milliseconds
        TimelineOpenTrace.settleIfQuiet(conversation)
        val settled = events().single { it.name == Stage.Settled.eventName }
        assertEquals(800L, settled.number("elapsedMs"))
        assertEquals(300L, settled.number("lastReplacementMs"))
        assertEquals(1L, settled.number("count"))

        clock += 5_000.milliseconds
        TimelineOpenTrace.settleIfQuiet(conversation)
        assertEquals(1, events().count { it.name == Stage.Settled.eventName })
    }

    @Test
    fun aLateLayerJoinsTheInFlightOpenButARequestAfterSettlingStartsANewOne() {
        val first = assertNotNull(TimelineOpenTrace.requested(conversation))
        assertEquals(first, TimelineOpenTrace.requestedIfAbsent(conversation))
        assertEquals(1, events().count { it.name == Stage.OpenRequested.eventName })

        clock += 6_000.milliseconds
        val stale = assertNotNull(TimelineOpenTrace.requestedIfAbsent(conversation))
        assertTrue(stale.value > first.value, "an open older than the join window is not joined")

        val explicit = assertNotNull(TimelineOpenTrace.requested(conversation))
        assertTrue(explicit.value > stale.value)
        assertEquals(3, events().count { it.name == Stage.OpenRequested.eventName })
        TimelineOpenTrace.mark(conversation, Stage.CacheReadEnd, count = 1)
        assertEquals(explicit.value, events().last().number("openId"))
    }

    @Test
    fun theFirstFrameGateMarksOncePerOpenAndOnlyForAFrameWithRows() {
        val gate = TimelineOpenTrace.FirstFrameGate(conversation)
        TimelineOpenTrace.requested(conversation)

        gate.onDraw(hasRows = false)
        assertTrue(events().none { it.name == Stage.FirstFrameWithRows.eventName })
        repeat(4) { gate.onDraw(hasRows = true) }
        assertEquals(1, events().count { it.name == Stage.FirstFrameWithRows.eventName })

        TimelineOpenTrace.requested(conversation)
        repeat(4) { gate.onDraw(hasRows = true) }
        assertEquals(2, events().count { it.name == Stage.FirstFrameWithRows.eventName })
    }

    @Test
    fun anOpenThatNeverSettlesStopsBeingPolled() {
        TimelineOpenTrace.requested(conversation)
        assertTrue(TimelineOpenTrace.isActive(conversation))

        clock += 31_000.milliseconds

        assertFalse(TimelineOpenTrace.isActive(conversation))
    }
}
