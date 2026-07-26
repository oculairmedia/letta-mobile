package com.letta.mobile.feature.chat.screen.messageactions

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class MessageActionTimestampFormatterTest {

    @Test
    fun `timestamp is formatted in the requested locale and local zone`() {
        val formatted = formatMessageActionTimestamp(
            timestamp = "2026-07-25T19:30:00Z",
            zoneId = ZoneId.of("America/Toronto"),
            locale = Locale.US,
        ).orEmpty()

        assertTrue(formatted.contains("Jul"))
        assertTrue(formatted.contains("25"))
        assertTrue(formatted.contains("2026"))
        assertTrue(formatted.contains("3:30"))
    }

    @Test
    fun `malformed timestamp falls back to the localized sheet title`() {
        assertNull(formatMessageActionTimestamp("not-a-timestamp"))
    }
}
