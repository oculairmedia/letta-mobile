package com.letta.mobile.feature.chat

import com.letta.mobile.data.a2ui.A2uiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class A2uiHistoryExtractorTest {
    @Test
    fun `extract strips valid a2ui blocks and returns decoded messages`() {
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
    fun `extract leaves invalid a2ui block visible`() {
        val raw = "<a2ui-json>{not-json}</a2ui-json>"

        val extraction = A2uiHistoryExtractor.extract(raw)

        assertEquals(raw, extraction.content)
        assertEquals(emptyList<A2uiMessage>(), extraction.messages)
    }
}
