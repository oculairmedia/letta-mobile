package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageActionPolicyTest {

    @Test
    fun `user and assistant text share copy and select actions`() {
        val user = availability(message(role = "user"))
        val assistant = availability(message(role = "assistant"))

        assertTrue(user.canCopy)
        assertTrue(user.canSelectText)
        assertTrue(assistant.canCopy)
        assertTrue(assistant.canSelectText)
    }

    @Test
    fun `send again is user-only and uses callback availability`() {
        assertTrue(availability(message(role = "user")).canSendAgain)
        assertFalse(availability(message(role = "assistant")).canSendAgain)
        assertFalse(availability(message(role = "user"), sendAgainAvailable = false).canSendAgain)
    }

    @Test
    fun `attachment messages do not expose lossy text-only send again`() {
        val message = message(role = "user").copy(
            attachments = listOf(UiImageAttachment(base64 = "image", mediaType = "image/png")),
        )

        assertFalse(availability(message).canSendAgain)
        assertTrue(availability(message).canCopy)
    }

    @Test
    fun `reasoning and tool messages do not expose message actions`() {
        assertFalse(availability(message(role = "assistant").copy(isReasoning = true)).hasActions)
        assertFalse(availability(message(role = "tool")).hasActions)
    }

    private fun availability(
        message: UiMessage,
        sendAgainAvailable: Boolean = true,
    ) = messageActionAvailability(
        message = message,
        copyText = "Structured message text",
        sendAgainAvailable = sendAgainAvailable,
    )

    private fun message(role: String) = UiMessage(
        id = "message-id",
        role = role,
        content = "Hello",
        timestamp = "2026-07-25T19:30:00Z",
    )
}
