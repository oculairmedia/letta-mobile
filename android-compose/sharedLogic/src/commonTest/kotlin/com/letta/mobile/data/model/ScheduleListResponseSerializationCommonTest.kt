package com.letta.mobile.data.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-9ejh3: serialization contract for [ScheduleListResponse].
 *
 * The mobile Schedules page failed to load against shim/local-runtime Letta
 * servers because `GET /v1/agents/{id}/schedule` returns a bare JSON *array*
 * (`[ {...} ]`), while native servers return the wrapped *object*
 * (`{ "scheduled_messages": [...] }`). A custom serializer (PR #648) handled
 * both shapes, but it was inadvertently reverted by PR #650, so decoding a
 * shim array threw a SerializationException and the page landed in a broken /
 * error state instead of showing the schedules (or an empty state).
 *
 * These tests pin BOTH wire shapes — and their empty variants — so the
 * regression cannot silently return.
 */
class ScheduleListResponseSerializationCommonTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val scheduleObjectJson =
        """{"id":"s1","agent_id":"a1","message":{"messages":[{"content":"hello","role":"user"}]},""" +
            """"schedule":{"type":"one-time","scheduled_at":1700000000.0}}"""

    // ── Shim shape: bare JSON array ──────────────────────────────────────

    @Test
    fun `decodes shim bare array payload with data into scheduled messages`() {
        val raw = "[$scheduleObjectJson]"

        val decoded = json.decodeFromString<ScheduleListResponse>(raw)

        assertEquals(1, decoded.scheduledMessages.size)
        assertEquals("s1", decoded.scheduledMessages.first().id)
        assertEquals(false, decoded.hasNextPage)
    }

    @Test
    fun `decodes shim empty array into empty list without throwing`() {
        val decoded = json.decodeFromString<ScheduleListResponse>("[]")

        assertTrue(decoded.scheduledMessages.isEmpty())
        assertEquals(false, decoded.hasNextPage)
    }

    // ── Native shape: wrapped object ─────────────────────────────────────

    @Test
    fun `decodes native wrapped object payload with data`() {
        val raw = """{"has_next_page":true,"scheduled_messages":[$scheduleObjectJson]}"""

        val decoded = json.decodeFromString<ScheduleListResponse>(raw)

        assertEquals(1, decoded.scheduledMessages.size)
        assertEquals("s1", decoded.scheduledMessages.first().id)
        assertEquals(true, decoded.hasNextPage)
    }

    @Test
    fun `decodes native wrapped object with empty list`() {
        val raw = """{"has_next_page":false,"scheduled_messages":[]}"""

        val decoded = json.decodeFromString<ScheduleListResponse>(raw)

        assertTrue(decoded.scheduledMessages.isEmpty())
        assertEquals(false, decoded.hasNextPage)
    }

    // ── Round-trip: encode emits the native wire keys ────────────────────

    @Test
    fun `encodes using native wire keys and round-trips`() {
        val original = json.decodeFromString<ScheduleListResponse>("[$scheduleObjectJson]")

        val encoded = json.encodeToString(ScheduleListResponseSerializer, original)

        assertTrue("\"scheduled_messages\"" in encoded, "Expected scheduled_messages key in: $encoded")
        val reDecoded = json.decodeFromString<ScheduleListResponse>(encoded)
        assertEquals(original.scheduledMessages.map { it.id }, reDecoded.scheduledMessages.map { it.id })
    }
}
