package com.letta.mobile.data.timeline

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [Timeline] data model.
 *
 * Mirrors the POC CLI tests in `poc/chat-cli/src/test/kotlin/com/letta/poc/TimelineTest.kt`
 * to ensure the mobile port preserves the validated invariants.
 */
class TimelineTest {

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

    @Test(expected = IllegalArgumentException::class)
    fun `append rejects out-of-order`() {
        val t = Timeline("c1").append(local("a", 5.0))
        t.append(local("b", 3.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `append rejects duplicate otid`() {
        val t = Timeline("c1").append(local("dup", 1.0))
        t.append(confirmed("dup", 2.0))
    }

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
}
