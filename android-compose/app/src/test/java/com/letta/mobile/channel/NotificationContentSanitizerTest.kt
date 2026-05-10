package com.letta.mobile.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationContentSanitizerTest {

    @Test
    fun `single-character preview uses fallback instead of raw token`() {
        val result = NotificationContentSanitizer.sanitizePreview(
            raw = "I",
            fallback = "Open the conversation to see the latest update.",
        )

        assertTrue(result.fallbackApplied)
        assertEquals(NotificationTextFallbackReason.TooShort, result.fallbackReason)
        assertEquals("Open the conversation to see the latest update.", result.text)
    }

    @Test
    fun `punctuation-only preview uses fallback`() {
        val result = NotificationContentSanitizer.sanitizePreview(
            raw = " ...!? ",
            fallback = "Open the conversation to see the latest update.",
        )

        assertTrue(result.fallbackApplied)
        assertEquals(NotificationTextFallbackReason.PunctuationOnly, result.fallbackReason)
        assertEquals("Open the conversation to see the latest update.", result.text)
    }

    @Test
    fun `preview trims controls and collapses whitespace`() {
        val result = NotificationContentSanitizer.sanitizePreview(
            raw = "\u0000  Hello\n\tthere  ",
            fallback = "fallback",
        )

        assertFalse(result.fallbackApplied)
        assertEquals(NotificationTextFallbackReason.None, result.fallbackReason)
        assertEquals("Hello there", result.text)
    }

    @Test
    fun `single-letter agent title is still allowed`() {
        val result = NotificationContentSanitizer.sanitizeTitle(raw = "A", fallback = "Letta update")

        assertFalse(result.fallbackApplied)
        assertEquals("A", result.text)
    }
}
