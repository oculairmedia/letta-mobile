package com.letta.mobile.data.timeline

import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimelineStateDumpTest {

    @BeforeTest
    fun setUp() {
        Telemetry.timelineDumpEnabled.set(true)
        Telemetry.clear()
    }

    @AfterTest
    fun tearDown() {
        Telemetry.timelineDumpEnabled.set(false)
        Telemetry.clear()
    }

    @Test
    fun testDumpTimelineState_whenDisabled_doesNothing() {
        Telemetry.timelineDumpEnabled.set(false)
        val timeline = Timeline("conv1", persistentListOf())
        dumpTimelineState("testPhase", "conv1", timeline)
        val snapshot = Telemetry.snapshot()
        assertTrue(snapshot.isEmpty())
    }

    @Test
    fun testDumpTimelineState_emptyTimeline_emitsSummaryOnly() {
        val timeline = Timeline("conv1", persistentListOf())
        dumpTimelineState("testPhase", "conv1", timeline)
        
        val snapshot = Telemetry.snapshot()
        assertEquals(1, snapshot.size)
        val event = snapshot.first()
        assertEquals("TimelineState", event.tag)
        assertEquals("testPhase.summary", event.name)
        assertEquals("conv1", event.attrs["conversationId"])
        assertEquals(0, event.attrs["eventCount"])
        assertEquals("<null>", event.attrs["liveCursor"])
    }

    @Test
    fun testDumpTimelineState_populatedTimeline_emitsSummaryAndEvents() {
        val longContent = "1234567890".repeat(5) // 50 chars, prefix len is 40
        val localEvent = TimelineEvent.Local(
            position = 1.0,
            otid = "otid_local",
            content = "local content\nwith newline",
            sentAt = timelineNow(),
            deliveryState = DeliveryState.SENDING
        )
        val confirmedEvent = TimelineEvent.Confirmed(
            position = 2.0,
            otid = "otid_conf",
            serverId = "srv_1",
            content = longContent,
            messageType = TimelineMessageType.ASSISTANT,
            date = timelineNow(),
            runId = "run_1",
            stepId = "step_1"
        )
        val timeline = Timeline(
            conversationId = "conv1",
            events = persistentListOf(localEvent, confirmedEvent),
            liveCursor = "cursor_1"
        )
        
        dumpTimelineState("testPhase", "conv1", timeline)
        
        val snapshot = Telemetry.snapshot().reversed() // Oldest first
        assertEquals(3, snapshot.size)
        
        val summary = snapshot[0]
        assertEquals("TimelineState", summary.tag)
        assertEquals("testPhase.summary", summary.name)
        assertEquals(2, summary.attrs["eventCount"])
        assertEquals("cursor_1", summary.attrs["liveCursor"])
        
        val localDump = snapshot[1]
        assertEquals("testPhase.event", localDump.name)
        assertEquals("Local", localDump.attrs["kind"])
        assertEquals(1.0, localDump.attrs["position"])
        assertEquals("otid_local", localDump.attrs["otid"])
        assertEquals("<none>", localDump.attrs["serverId"])
        assertEquals(26, localDump.attrs["contentLen"])
        assertEquals("local content with newline", localDump.attrs["contentPrefix"]) // Newline replaced
        
        val confDump = snapshot[2]
        assertEquals("testPhase.event", confDump.name)
        assertEquals("Confirmed", confDump.attrs["kind"])
        assertEquals(2.0, confDump.attrs["position"])
        assertEquals("otid_conf", confDump.attrs["otid"])
        assertEquals("srv_1", confDump.attrs["serverId"])
        assertEquals(50, confDump.attrs["contentLen"])
        assertEquals("1234567890123456789012345678901234567890", confDump.attrs["contentPrefix"]) // Truncated to 40
    }
}
