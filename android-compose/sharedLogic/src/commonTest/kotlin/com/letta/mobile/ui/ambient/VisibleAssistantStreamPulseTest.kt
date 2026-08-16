package com.letta.mobile.ui.ambient

import kotlin.test.Test
import kotlin.test.assertEquals

class VisibleAssistantStreamPulseTest {
    @Test
    fun `visible assistant growth increments pulse while streaming`() {
        val seeded = reduceVisibleAssistantStreamPulse(
            VisibleAssistantStreamPulseState(),
            isStreaming = true,
            tailId = "assistant-1",
            contentLength = 5,
        )
        val grown = reduceVisibleAssistantStreamPulse(
            seeded,
            isStreaming = true,
            tailId = "assistant-1",
            contentLength = 11,
        )

        assertEquals(1L, grown.pulse)
    }

    @Test
    fun `new tail seeds baseline without a false pulse`() {
        val previous = VisibleAssistantStreamPulseState(pulse = 4, tailId = "old", contentLength = 30)
        val next = reduceVisibleAssistantStreamPulse(previous, true, "new", 8)

        assertEquals(4L, next.pulse)
        assertEquals("new", next.tailId)
    }

    @Test
    fun `hydration and terminal changes do not pulse`() {
        val previous = VisibleAssistantStreamPulseState(pulse = 2, tailId = "assistant-1", contentLength = 5)
        val hydrated = reduceVisibleAssistantStreamPulse(previous, false, "assistant-1", 50)
        val terminal = reduceVisibleAssistantStreamPulse(hydrated, false, "assistant-1", 60)

        assertEquals(2L, hydrated.pulse)
        assertEquals(2L, terminal.pulse)
    }

    @Test
    fun `duplicates and shrinking projections do not pulse`() {
        val previous = VisibleAssistantStreamPulseState(pulse = 3, tailId = "assistant-1", contentLength = 20)

        assertEquals(3L, reduceVisibleAssistantStreamPulse(previous, true, "assistant-1", 20).pulse)
        assertEquals(3L, reduceVisibleAssistantStreamPulse(previous, true, "assistant-1", 12).pulse)
    }
}
