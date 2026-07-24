package com.letta.mobile.desktop

import com.letta.mobile.data.model.UiMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopNotificationPreviewTest {
    private fun message(
        role: String,
        content: String,
        isReasoning: Boolean = false,
        isError: Boolean = false,
        isPending: Boolean = false,
    ) = UiMessage(
        id = "m-${'$'}content",
        role = role,
        content = content,
        timestamp = "2026-07-24T11:35:00Z",
        isReasoning = isReasoning,
        isError = isError,
        isPending = isPending,
    )

    @Test
    fun picksLastSubstantiveAssistantReply() {
        val messages = listOf(
            message("assistant", "First answer"),
            message("user", "good"),
            message("assistant", "Thinking...", isReasoning = true),
            message("assistant", "Here whenever you need me."),
        )
        assertEquals("Here whenever you need me.", notificationReplyPreview(messages))
    }

    @Test
    fun skipsErrorPendingAndBlankMessages() {
        val messages = listOf(
            message("assistant", "Real reply"),
            message("assistant", "boom", isError = true),
            message("assistant", "", isPending = true),
        )
        assertEquals("Real reply", notificationReplyPreview(messages))
        assertNull(notificationReplyPreview(listOf(message("user", "hi"))))
        assertNull(notificationReplyPreview(null))
    }

    @Test
    fun collapsesWhitespaceAndTruncates() {
        val long = "line one\n\nline  two " + "x".repeat(300)
        val preview = notificationReplyPreview(listOf(message("assistant", long)))!!
        assertTrue(preview.startsWith("line one line two"))
        assertTrue(preview.length <= 180)
        assertTrue(preview.endsWith("…"))
    }
}
