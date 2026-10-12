package com.letta.mobile.data.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * letta-mobile-dxz5s: a conversation's own model override decodes from the host's records.
 *
 * letta-code's local backend answers `conversation_list` / `conversation_retrieve` with the whole
 * `conversation.json` record (`normalizeConversationRecord` spreads it; the listener forwards the
 * page as is), so the wire carries `model`, `model_settings` and `context_window_limit`. The
 * payload below is the shape of local-conv-517's record as read on the host on 2026-10-11.
 */
class ConversationHostModelDecodeTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun aHostRecordWithAnOverrideDecodesItsModelWindowAndEffort() {
        val listed = json.decodeFromString(ListSerializer(Conversation.serializer()), HOST_CONVERSATION_LIST)

        val conversation = listed.single()
        assertEquals("anthropic/claude-opus-5-5", conversation.model)
        assertEquals(1_000_000, conversation.contextWindowLimit)
        assertEquals(1_000_000, conversation.hostContextWindowLimit)
        assertEquals("anthropic", conversation.modelSettings?.providerType)
        assertEquals(128_000, conversation.modelSettings?.maxTokens)
        assertEquals("low", conversation.modelSettings?.effectiveEffort)
    }

    @Test
    fun theWindowFallsBackToTheModelSettingsLimit() {
        val conversation = json.decodeFromString(
            Conversation.serializer(),
            """{"id":"c","agent_id":"a","model":"openai/gpt","model_settings":{"context_window_limit":400000,"effort":"high"}}""",
        )
        assertEquals(400_000, conversation.hostContextWindowLimit)
        assertEquals("high", conversation.modelSettings?.effectiveEffort)
    }

    @Test
    fun aRecordWithoutAnOverrideOrFromAnOlderHostDecodesToNone() {
        val plain = json.decodeFromString(Conversation.serializer(), """{"id":"c","agent_id":"a","model":null,"model_settings":null}""")
        val older = json.decodeFromString(Conversation.serializer(), """{"id":"c","agent_id":"a","summary":"s"}""")

        listOf(plain, older).forEach { conversation ->
            assertNull(conversation.model)
            assertNull(conversation.modelSettings)
            assertNull(conversation.hostContextWindowLimit)
        }
    }

    private companion object {
        const val HOST_CONVERSATION_LIST = """[{
            "id": "local-conv-517",
            "agent_id": "agent-c356b54a",
            "archived": false,
            "archived_at": null,
            "created_at": "2026-10-01T10:00:00.000Z",
            "updated_at": "2026-10-11T09:00:00.000Z",
            "last_message_at": "2026-10-11T09:00:00.000Z",
            "summary": "PM letta-mobile",
            "in_context_message_ids": ["message-1", "message-2"],
            "model": "anthropic/claude-opus-5-5",
            "model_settings": {
                "provider_type": "anthropic",
                "context_window_limit": 1000000,
                "max_tokens": 128000,
                "effort": "low",
                "thinking": {"type": "enabled", "budget_tokens": 10000},
                "reasoning_effort": "low"
            },
            "context_window_limit": 1000000
        }]"""
    }
}
