package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.JsonPrimitive
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
        serverId: String = "server-$otid",
        date: Instant = Instant.now(),
    ): TimelineEvent.Confirmed = TimelineEvent.Confirmed(
        position = pos,
        otid = otid,
        content = "confirmed",
        serverId = serverId,
        messageType = type,
        date = date,
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
    fun `replaceLocal preserves local otid when confirmed otid belongs to another event`() {
        val t = Timeline("c1")
            .append(local("local-user", 1.0, "hi"))
            .append(confirmed("server-user", 2.0, TimelineMessageType.USER))

        val updated = t.replaceLocal(
            "local-user",
            confirmed("server-user", 99.0, TimelineMessageType.USER),
        )

        assertEquals(listOf("local-user", "server-user"), updated.events.map { it.otid })
        assertEquals(updated.events.size, updated.events.map { it.otid }.toSet().size)
    }

    @Test
    fun `replaceByServerId drops pre existing duplicate otid outside replacement slot`() {
        Telemetry.clear()
        val existing = confirmed("stable", 1.0, TimelineMessageType.ASSISTANT).copy(serverId = "srv-1")
        val duplicate = confirmed("stable", 2.0, TimelineMessageType.USER).copy(serverId = "srv-2")
        val t = Timeline("c1", events = listOf(existing, duplicate))
        Telemetry.clear()

        val updated = t.replaceByServerId(
            existing.copy(content = "updated"),
        )

        assertEquals(1, updated.events.size)
        assertEquals("stable", updated.events.single().otid)
        assertEquals("updated", updated.events.single().content)
        assertTrue(
            "Expected duplicate drop telemetry",
            Telemetry.snapshot().any {
                it.tag == "Timeline" && it.name == "replaceByServerId.duplicateOtidDropped"
            },
        )
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
    fun `mergeServerMessages inserts by date dedupes and skips tool returns`() {
        val timeline = Timeline("c1")
            .append(
                confirmed(
                    otid = "older",
                    pos = 1.0,
                    type = TimelineMessageType.USER,
                    serverId = "server-older",
                    date = Instant.parse("2026-05-19T06:00:00Z"),
                )
            )
            .append(
                confirmed(
                    otid = "newer",
                    pos = 4.0,
                    type = TimelineMessageType.ASSISTANT,
                    serverId = "server-newer",
                    date = Instant.parse("2026-05-19T06:30:00Z"),
                )
            )

        val (merged, insertedCount) = timeline.mergeServerMessages(
            listOf(
                UserMessage(
                    id = "server-duplicate-otid",
                    contentRaw = JsonPrimitive("duplicate otid"),
                    otid = "older",
                    date = "2026-05-19T06:05:00Z",
                ),
                AssistantMessage(
                    id = "server-newer",
                    contentRaw = JsonPrimitive("duplicate server id"),
                    otid = "fresh-server-id-duplicate",
                    date = "2026-05-19T06:10:00Z",
                ),
                ToolReturnMessage(
                    id = "tool-return",
                    toolCallId = "toolu_1",
                    toolReturnRaw = JsonPrimitive("hidden output"),
                    date = "2026-05-19T06:15:00Z",
                ),
                UserMessage(
                    id = "fresh-between",
                    contentRaw = JsonPrimitive("fresh prompt"),
                    date = "2026-05-19T06:20:00Z",
                ),
            )
        )

        val confirmed = merged.events.filterIsInstance<TimelineEvent.Confirmed>()
        assertEquals(1, insertedCount)
        assertEquals(listOf("server-older", "fresh-between", "server-newer"), confirmed.map { it.serverId })
        assertEquals("fresh prompt", confirmed[1].content)
        assertTrue("fresh message should be positioned between existing dated events", confirmed[1].position in 1.0..4.0)
        assertEquals(3, confirmed.size)
        assertNull(merged.findByServerId("tool-return", TimelineMessageType.TOOL_RETURN))
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
}
