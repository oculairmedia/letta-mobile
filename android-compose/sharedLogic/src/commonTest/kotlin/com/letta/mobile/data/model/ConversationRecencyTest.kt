package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** letta-mobile-by4vm.72: the one conversation recency order every list surface shares. */
class ConversationRecencyTest {

    @Test
    fun parserAcceptsZAndOffsetFormsAnyFractionNaiveAndSpaceSeparatedValues() {
        assertEquals(1_767_261_600_000L, ConversationRecency.epochMillisOrNull("2026-01-01T10:00:00Z"))
        assertEquals(1_767_261_600_000L, ConversationRecency.epochMillisOrNull("2026-01-01T10:00:00+00:00"))
        assertEquals(1_767_254_400_000L, ConversationRecency.epochMillisOrNull("2026-01-01T10:00:00+02:00"))
        assertEquals(1_767_261_600_500L, ConversationRecency.epochMillisOrNull("2026-01-01T10:00:00.5Z"))
        assertEquals(1_767_259_800_123L, ConversationRecency.epochMillisOrNull("2026-01-01T09:30:00.123456+00:00"))
        assertEquals(1_767_261_600_000L, ConversationRecency.epochMillisOrNull("2026-01-01T10:00:00"))
        assertEquals(1_767_261_600_000L, ConversationRecency.epochMillisOrNull("2026-01-01 10:00:00+00:00"))
        assertNull(ConversationRecency.epochMillisOrNull("not a timestamp"))
        assertNull(ConversationRecency.epochMillisOrNull("  "))
        assertNull(ConversationRecency.epochMillisOrNull(null))
    }

    @Test
    fun newestFirstFallsBackPerFieldThenBreaksTiesById() {
        val conversations = listOf(
            conversation("a-plus-two", lastMessageAt = "2026-01-01T10:00:00+02:00"),
            conversation("b-fraction", lastMessageAt = "2026-01-01T10:00:00.5Z"),
            conversation("c-z", lastMessageAt = "2026-01-01T10:00:00Z"),
            conversation("d-updated-only", updatedAt = "2026-01-01T09:30:00.123456+00:00", createdAt = "2025-01-01T00:00:00Z"),
            // An unparseable last_message_at falls through to created_at, not to 0.
            conversation("e-garbage-created", lastMessageAt = "garbage", createdAt = "2025-06-01T00:00:00Z"),
            conversation("zzz-none"),
            conversation("zz-none"),
        )

        assertEquals(
            listOf("b-fraction", "c-z", "d-updated-only", "a-plus-two", "e-garbage-created", "zz-none", "zzz-none"),
            conversations.sortedWith(ConversationRecency.newestFirst).map { it.id.value },
        )
    }

    private fun conversation(
        id: String,
        lastMessageAt: String? = null,
        updatedAt: String? = null,
        createdAt: String? = null,
    ) = Conversation(
        id = ConversationId(id),
        agentId = AgentId("agent-1"),
        lastMessageAt = lastMessageAt,
        updatedAt = updatedAt,
        createdAt = createdAt,
    )
}
