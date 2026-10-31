package com.letta.mobile.desktop

import com.letta.mobile.data.chat.projection.ChatDisplayMode
import com.letta.mobile.data.chat.projection.buildChatRenderModel
import com.letta.mobile.data.chat.projection.chronologicalMessages
import com.letta.mobile.data.model.UiMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopNotificationPreviewTest {
    private fun message(role: String, content: String) = UiMessage(
        id = "m-${'$'}content",
        role = role,
        content = content,
        timestamp = "2026-07-24T11:35:00Z",
    )

    @Test
    fun picksLastSubstantiveAssistantReply() {
        val messages = listOf(
            message("assistant", "First answer"),
            message("user", "good"),
            message("assistant", "Thinking...").copy(isReasoning = true),
            message("assistant", "Here whenever you need me."),
        )
        assertEquals("Here whenever you need me.", notificationReplyPreview(messages))
    }

    @Test
    fun skipsErrorPendingAndBlankMessages() {
        val messages = listOf(
            message("assistant", "Real reply"),
            message("assistant", "boom").copy(isError = true),
            message("assistant", "").copy(isPending = true),
        )
        assertEquals("Real reply", notificationReplyPreview(messages))
        assertNull(notificationReplyPreview(listOf(message("user", "hi"))))
        assertNull(notificationReplyPreview(null))
    }

    // The canonical route keeps no message map; the toast reads its live overlay, which is newest first.
    @Test
    fun readsTheNewestReplyFromANewestFirstRenderOverlay() {
        val overlay = buildChatRenderModel(
            messages = listOf(
                message("assistant", "Old reply").copy(id = "a0", runId = "run-0"),
                message("user", "hello").copy(id = "u1"),
                message("assistant", "Earlier step").copy(id = "a1", runId = "run-1"),
                message("assistant", "Final answer").copy(id = "a2", runId = "run-1"),
            ),
            mode = ChatDisplayMode.Interactive,
        ).renderItems
        assertEquals("Final answer", notificationReplyPreview(chronologicalMessages(overlay)))
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
