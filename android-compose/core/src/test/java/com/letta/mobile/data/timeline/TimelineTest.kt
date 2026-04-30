package com.letta.mobile.data.timeline

import com.letta.mobile.util.Telemetry
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * Unit tests for the [Timeline] data model.
 *
 * Mirrors the POC CLI tests in `poc/chat-cli/src/test/kotlin/com/letta/poc/TimelineTest.kt`
 * to ensure the mobile port preserves the validated invariants.
 */
@Tag("integration")
class TimelineTest {

    @After
    fun tearDown() {
        Telemetry.clear()
    }

    private fun local(otid: String, pos: Double, content: String = "msg"): TimelineEvent.Local =
        TimelineEvent.Local(
            position = pos,
            otid = otid,
            content = content,
            role = Role.USER,
            sentAt = Instant.now(),
            deliveryState = DeliveryState.SENDING,
        )

    private fun confirmed(
        otid: String,
        pos: Double,
        type: TimelineMessageType = TimelineMessageType.ASSISTANT,
    ): TimelineEvent.Confirmed = TimelineEvent.Confirmed(
        position = pos,
        otid = otid,
        content = "confirmed",
        serverId = "server-$otid",
        messageType = type,
        date = Instant.now(),
        runId = null,
        stepId = null,
    )

    @Test
    fun `empty timeline is valid`() {
        val t = Timeline("c1")
        assertEquals(0, t.events.size)
    }

    @Test
    fun `append respects ordering`() {
        val t = Timeline("c1")
            .append(local("a", 1.0))
            .append(confirmed("b", 2.0))
        assertEquals(2, t.events.size)
        assertEquals("a", t.events[0].otid)
        assertEquals("b", t.events[1].otid)
    }

    // NOTE: Previous tests asserted that append() throws IllegalArgumentException
    // on out-of-order position or duplicate otid. As of letta-mobile-4jmg we tolerate
    // both cases (log telemetry, bump position, drop duplicate) to avoid crashing the
    // chat screen on transient races. See defensive-behavior tests at the bottom.

    @Test
    fun `replaceLocal swaps in place preserving position`() {
        val t = Timeline("c1")
            .append(local("user-1", 1.0, "hi"))
            .append(confirmed("reply-1", 2.0))

        // Intentional wrong position — should be overridden by the Local's position
        val confirmedUser = confirmed("user-1", 99.0, TimelineMessageType.USER)
        val updated = t.replaceLocal("user-1", confirmedUser)

        val first = updated.events[0]
        assertTrue(first is TimelineEvent.Confirmed)
        assertEquals(1.0, first.position, 0.0)
        assertEquals("user-1", first.otid)
        assertEquals(2.0, updated.events[1].position, 0.0)
    }

    @Test
    fun `replaceLocal with unknown otid appends via insertOrdered`() {
        val t = Timeline("c1").append(local("a", 1.0))
        val updated = t.replaceLocal("stranger", confirmed("stranger", 5.0))
        assertEquals(2, updated.events.size)
    }

    @Test
    fun `insertOrdered places event at correct position`() {
        val t = Timeline("c1")
            .append(confirmed("a", 1.0))
            .append(confirmed("c", 3.0))
        val updated = t.insertOrdered(confirmed("b", 2.0))
        assertEquals(listOf("a", "b", "c"), updated.events.map { it.otid })
    }

    @Test
    fun `insertOrdered dedupes by otid`() {
        val t = Timeline("c1").append(confirmed("x", 1.0))
        val updated = t.insertOrdered(confirmed("x", 99.0))
        assertEquals(1, updated.events.size)
        assertEquals(1.0, updated.events[0].position, 0.0)
    }

    @Test
    fun `markSent transitions local state`() {
        val t = Timeline("c1").append(local("a", 1.0))
        val updated = t.markSent("a")
        val event = updated.events[0] as TimelineEvent.Local
        assertEquals(DeliveryState.SENT, event.deliveryState)
    }

    @Test
    fun `markFailed transitions local state`() {
        val t = Timeline("c1").append(local("a", 1.0))
        val updated = t.markFailed("a")
        val event = updated.events[0] as TimelineEvent.Local
        assertEquals(DeliveryState.FAILED, event.deliveryState)
    }

    @Test
    fun `markSent is no-op on confirmed event`() {
        val t = Timeline("c1").append(confirmed("a", 1.0))
        val updated = t.markSent("a")
        assertEquals(t, updated)
    }

    @Test
    fun `nextLocalPosition increments`() {
        val t0 = Timeline("c1")
        assertEquals(1.0, t0.nextLocalPosition(), 0.0)

        val t1 = t0.append(local("a", 1.0))
        assertEquals(2.0, t1.nextLocalPosition(), 0.0)

        val t2 = t1.append(confirmed("b", 5.0))
        assertEquals(6.0, t2.nextLocalPosition(), 0.0)
    }

    @Test
    fun `findByOtid locates events`() {
        val t = Timeline("c1")
            .append(local("a", 1.0))
            .append(confirmed("b", 2.0))
        assertNotNull(t.findByOtid("a"))
        assertNotNull(t.findByOtid("b"))
        assertNull(t.findByOtid("nope"))
    }

    // === Scenario-level tests (POC parity) ===

    @Test
    fun `scenario - basic send confirm reply`() {
        val userOtid = "user-hi"
        var t = Timeline("c1").append(local(userOtid, 1.0, "hi"))

        val assistantOtid = "assistant-reply-01"
        t = t.append(confirmed(assistantOtid, 2.0, TimelineMessageType.ASSISTANT))

        val userConfirmed = confirmed(userOtid, 999.0, TimelineMessageType.USER)
        t = t.replaceLocal(userOtid, userConfirmed)

        assertEquals(userOtid, t.events[0].otid)
        assertEquals(assistantOtid, t.events[1].otid)
        assertTrue(t.events[0] is TimelineEvent.Confirmed)
    }

    @Test
    fun `scenario - rapid consecutive sends preserve order`() {
        var t = Timeline("c1")
        val otids = (1..5).map { "msg-$it" }
        otids.forEach {
            t = t.append(local(it, t.nextLocalPosition()))
        }
        assertEquals(otids, t.events.map { it.otid })
    }

    @Test
    fun `scenario - identical content sent twice produces two events`() {
        val t = Timeline("c1")
            .append(local("otid-1", 1.0, "hi"))
            .append(local("otid-2", 2.0, "hi"))
        assertEquals(2, t.events.size)
        assertEquals("hi", t.events[0].content)
        assertEquals("hi", t.events[1].content)
    }

    @Test
    fun `scenario - pagination backfill inserts in correct position`() {
        var t = Timeline("c1")
            .append(confirmed("recent-1", 100.0))
            .append(confirmed("recent-2", 101.0))
        t = t.insertOrdered(confirmed("old-1", 50.0))
        assertEquals(listOf("old-1", "recent-1", "recent-2"), t.events.map { it.otid })
    }

    // === Defensive behavior tests (letta-mobile-4jmg) ===

    @Test
    fun `init logs telemetry for position collision`() {
        Telemetry.clear()
        val events = listOf(
            local("a", 1.0, "first"),
            confirmed("b", 1.0),  // position collision
        )
        
        val t = Timeline("c1", events = events)
        
        // The events should still be present (no crash in production)
        assertEquals(2, t.events.size)
        
        // Verify telemetry error was logged
        val telemetryEvents = Telemetry.snapshot()
        val positionViolation = telemetryEvents.any {
            it.tag == "Timeline" && it.name == "init.positionViolation" && it.level == Telemetry.Level.ERROR
        }
        assertTrue("Expected positionViolation telemetry event", positionViolation)
    }

    @Test
    fun `init logs telemetry for duplicate otid`() {
        Telemetry.clear()
        val events = listOf(
            local("dup", 1.0, "first"),
            confirmed("dup", 2.0),  // otid duplicate
        )
        
        val t = Timeline("c1", events = events)
        
        // Should not crash (defensive behavior)
        assertEquals(2, t.events.size)
        
        // Verify telemetry error was logged
        val telemetryEvents = Telemetry.snapshot()
        val otidDuplicate = telemetryEvents.any {
            it.tag == "Timeline" && it.name == "init.otidDuplicates" && it.level == Telemetry.Level.ERROR
        }
        assertTrue("Expected otidDuplicates telemetry event", otidDuplicate)
    }

    @Test
    fun `append drops duplicate otid and logs warning instead of crashing`() {
        Telemetry.clear()
        val t = Timeline("c1").append(local("dup", 1.0))
        val afterDup = t.append(local("dup", 2.0))
        // Duplicate is dropped — timeline keeps the original event.
        assertEquals(1, afterDup.events.size)
        assertEquals("dup", afterDup.events.single().otid)
        assertTrue(
            "Expected append.duplicateOtid telemetry",
            Telemetry.snapshot().any {
                it.tag == "Timeline" && it.name == "append.duplicateOtid" && it.level == Telemetry.Level.WARN
            },
        )
    }

    @Test
    fun `append bumps colliding position instead of crashing`() {
        Telemetry.clear()
        val t = Timeline("c1").append(local("a", 5.0))
        // Attempt to append at a position that would break strict-monotonic.
        val bumped = t.append(confirmed("b", 3.0))
        // Event accepted — position bumped to last+1.0.
        assertEquals(2, bumped.events.size)
        assertEquals(5.0, bumped.events[0].position, 0.0001)
        assertEquals(6.0, bumped.events[1].position, 0.0001)
        assertEquals("b", bumped.events[1].otid)
        assertTrue(
            "Expected append.positionBumped telemetry",
            Telemetry.snapshot().any {
                it.tag == "Timeline" && it.name == "append.positionBumped" && it.level == Telemetry.Level.WARN
            },
        )
    }

    // ---------------------------------------------------------------
    // letta-mobile-c87t: Client Mode fuzzy reconcile tests
    // ---------------------------------------------------------------

    private fun clientModeLocal(
        otid: String,
        pos: Double,
        content: String,
        sentAt: Instant = Instant.now(),
    ): TimelineEvent.Local = TimelineEvent.Local(
        position = pos,
        otid = otid,
        content = content,
        role = Role.USER,
        sentAt = sentAt,
        deliveryState = DeliveryState.SENT,
        source = MessageSource.CLIENT_MODE_HARNESS,
    )

    private fun clientModeAssistantLocal(
        otid: String,
        pos: Double,
        content: String,
        sentAt: Instant = Instant.now(),
    ): TimelineEvent.Local = TimelineEvent.Local(
        position = pos,
        otid = otid,
        content = content,
        role = Role.ASSISTANT,
        sentAt = sentAt,
        deliveryState = DeliveryState.SENT,
        source = MessageSource.CLIENT_MODE_HARNESS,
        messageType = TimelineMessageType.ASSISTANT,
    )

    private fun confirmedUser(
        otid: String,
        pos: Double,
        content: String,
        date: Instant = Instant.now(),
        serverId: String = "server-$otid",
    ): TimelineEvent.Confirmed = TimelineEvent.Confirmed(
        position = pos,
        otid = otid,
        content = content,
        serverId = serverId,
        messageType = TimelineMessageType.USER,
        date = date,
        runId = null,
        stepId = null,
    )

    private fun confirmedAssistant(
        otid: String,
        pos: Double,
        content: String,
        date: Instant = Instant.now(),
        serverId: String = "server-$otid",
    ): TimelineEvent.Confirmed = TimelineEvent.Confirmed(
        position = pos,
        otid = otid,
        content = content,
        serverId = serverId,
        messageType = TimelineMessageType.ASSISTANT,
        date = date,
        runId = null,
        stepId = null,
    )

    @Test
    fun `collapseClientModeFuzzyMatch collapses matching local within window`() {
        val now = Instant.now()
        val t = Timeline("c1")
            .append(clientModeLocal("cm-1", 1.0, "hello", sentAt = now.minusMillis(500)))

        val incoming = confirmedUser("server-otid", 99.0, "hello", date = now, serverId = "srv-1")
        val result = t.collapseClientModeFuzzyMatch(incoming)

        assertNotNull("Expected collapse to fire", result.collapsed)
        assertEquals("cm-1", result.collapsed!!.localOtid)
        assertEquals("srv-1", result.collapsed.serverId)
        assertEquals(MessageSource.CLIENT_MODE_HARNESS, result.collapsed.source)
        assertEquals(1, result.timeline.events.size)
        val swapped = result.timeline.events[0]
        assertTrue(swapped is TimelineEvent.Confirmed)
        // Position preserved from the Local — guards against visual jump.
        assertEquals(1.0, swapped.position, 0.0001)
    }

    @Test
    fun `collapseClientModeFuzzyMatch ignores LETTA_SERVER source local`() {
        val now = Instant.now()
        // Default source is LETTA_SERVER — must not be eligible for fuzzy collapse.
        val t = Timeline("c1").append(local("user-1", 1.0, "hello"))

        val incoming = confirmedUser("server-otid", 99.0, "hello", date = now)
        val result = t.collapseClientModeFuzzyMatch(incoming)

        assertNull("LETTA_SERVER local must not be collapsed", result.collapsed)
        // Timeline unchanged.
        assertEquals(1, result.timeline.events.size)
        assertTrue(result.timeline.events[0] is TimelineEvent.Local)
    }

    @Test
    fun `collapseClientModeFuzzyMatch ignores match outside window`() {
        val now = Instant.now()
        val t = Timeline("c1").append(
            clientModeLocal("cm-1", 1.0, "hello", sentAt = now.minusMillis(15_000))
        )

        val incoming = confirmedUser("server-otid", 99.0, "hello", date = now)
        val result = t.collapseClientModeFuzzyMatch(incoming, windowMillis = 10_000)

        assertNull("Match outside the configured window must not be collapsed", result.collapsed)
    }

    @Test
    fun `collapseClientModeFuzzyMatch ignores content mismatch`() {
        val now = Instant.now()
        val t = Timeline("c1").append(
            clientModeLocal("cm-1", 1.0, "hello", sentAt = now.minusMillis(200))
        )

        val incoming = confirmedUser("server-otid", 99.0, "goodbye", date = now)
        val result = t.collapseClientModeFuzzyMatch(incoming)

        assertNull("Content mismatch must not collapse", result.collapsed)
    }

    @Test
    fun `collapseClientModeFuzzyMatch collapses matching assistant local and preserves accumulated text`() {
        val now = Instant.now()
        val t = Timeline("c1").append(
            clientModeAssistantLocal("cm-assist-1", 1.0, "Hello world", sentAt = now.minusMillis(200))
        )

        val incoming = confirmedAssistant("server-otid", 99.0, "world", date = now, serverId = "srv-1")
        val result = t.collapseClientModeFuzzyMatch(incoming)

        assertNotNull("Assistant Confirmed events should collapse matching Client Mode locals", result.collapsed)
        assertEquals("cm-assist-1", result.collapsed!!.localOtid)
        assertEquals(1, result.timeline.events.size)
        val swapped = result.timeline.events[0] as TimelineEvent.Confirmed
        assertEquals(1.0, swapped.position, 0.0001)
        assertEquals(
            "Local accumulator should remain the baseline when the first Confirmed frame is only a later delta",
            "Hello world",
            swapped.content,
        )
    }

    @Test
    fun `collapseClientModeFuzzyMatch ignores unsupported non-user non-assistant confirmed message`() {
        val now = Instant.now()
        val t = Timeline("c1").append(
            clientModeAssistantLocal("cm-assist-1", 1.0, "hello", sentAt = now.minusMillis(200))
        )

        val incoming = TimelineEvent.Confirmed(
            position = 99.0,
            otid = "server-otid",
            content = "hello",
            serverId = "srv-1",
            messageType = TimelineMessageType.REASONING,
            date = now,
            runId = null,
            stepId = null,
        )
        val result = t.collapseClientModeFuzzyMatch(incoming)

        assertNull("Only USER and ASSISTANT Confirmed events are eligible for collapse", result.collapsed)
    }

    @Test
    fun `collapseClientModeFuzzyMatch picks most recent matching local within window`() {
        val now = Instant.now()
        // Two same-content client-mode locals — most recent should win.
        val t = Timeline("c1")
            .append(clientModeLocal("cm-old", 1.0, "ping", sentAt = now.minusMillis(8_000)))
            .append(clientModeLocal("cm-new", 2.0, "ping", sentAt = now.minusMillis(500)))

        val incoming = confirmedUser("server-otid", 99.0, "ping", date = now, serverId = "srv-1")
        val result = t.collapseClientModeFuzzyMatch(incoming)

        assertNotNull(result.collapsed)
        assertEquals("cm-new", result.collapsed!!.localOtid)
        // The older Local must remain in the timeline (not collapsed by this call).
        val oldStillThere = result.timeline.events.any { it.otid == "cm-old" }
        assertTrue("Older client-mode local should not be removed", oldStillThere)
    }

    @Test
    fun `collapseClientModeFuzzyMatch trace contains content prefix`() {
        val now = Instant.now()
        val longContent = "a".repeat(200)
        val t = Timeline("c1").append(
            clientModeLocal("cm-1", 1.0, longContent, sentAt = now.minusMillis(100))
        )

        val incoming = confirmedUser("server-otid", 99.0, longContent, date = now)
        val result = t.collapseClientModeFuzzyMatch(incoming)

        assertNotNull(result.collapsed)
        // Trace prefix is bounded — guards against telemetry bloat on long messages.
        assertTrue(
            "Content prefix should be at most 40 chars",
            result.collapsed!!.contentPrefix.length <= 40,
        )
    }
}
