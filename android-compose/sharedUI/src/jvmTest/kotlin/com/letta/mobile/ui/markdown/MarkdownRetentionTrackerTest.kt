package com.letta.mobile.ui.markdown

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownRetentionTrackerTest {
    @Test
    fun `append-only streaming retains the parser subtree`() {
        val tracker = MarkdownRetentionTracker()

        assertEquals(0, tracker.update("The response"))
        assertEquals(0, tracker.update("The response grows"))
        assertEquals(0, tracker.update("The response grows **bold**"))
    }

    @Test
    fun `shorter reconciled text resets the parser subtree`() {
        val tracker = MarkdownRetentionTracker()

        assertEquals(0, tracker.update("x".repeat(91)))
        assertEquals(1, tracker.update("x".repeat(87)))
    }

    @Test
    fun `same-length rewrite resets once and then retains appends`() {
        val tracker = MarkdownRetentionTracker()

        assertEquals(0, tracker.update("draft response"))
        assertEquals(1, tracker.update("final response"))
        assertEquals(1, tracker.update("final response with tail"))
    }
}
