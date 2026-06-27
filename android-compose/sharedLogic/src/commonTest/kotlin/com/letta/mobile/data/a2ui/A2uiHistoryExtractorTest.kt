package com.letta.mobile.data.a2ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class A2uiHistoryExtractorTest {
    @Test
    fun extractStripsValidA2uiBlocksAndReturnsDecodedMessages() {
        val extraction = A2uiHistoryExtractor.extract(
            """
            Intro
            <a2ui-json>
            [
              {"version":"v0.9","createSurface":{"surfaceId":"s1","catalogId":"basic"}},
              {"version":"v0.9","deleteSurface":{"surfaceId":"s1"}}
            ]
            </a2ui-json>
            Outro
            """.trimIndent()
        )

        assertEquals("Intro\n\nOutro", extraction.content)
        assertEquals(2, extraction.messages.size)
        assertTrue(extraction.messages[0] is A2uiMessage.CreateSurface)
        assertTrue(extraction.messages[1] is A2uiMessage.DeleteSurface)
    }

    @Test
    fun extractLeavesInvalidA2uiBlockVisible() {
        val raw = "<a2ui-json>{not-json}</a2ui-json>"

        val extraction = A2uiHistoryExtractor.extract(raw)

        assertEquals(raw, extraction.content)
        assertEquals(emptyList<A2uiMessage>(), extraction.messages)
    }

    @Test
    fun extractRepairsTruncatedA2uiJson() {
        val extraction = A2uiHistoryExtractor.extract(
            """
            <a2ui-json>
            {"createSurface":{"surfaceId":"s1","catalogId":"basic"
            </a2ui-json>
            """.trimIndent()
        )

        assertEquals("", extraction.content)
        assertEquals(1, extraction.messages.size)
        assertTrue(extraction.messages[0] is A2uiMessage.CreateSurface)
    }
}
