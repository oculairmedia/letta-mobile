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

    @Test
    fun `fork and edit follow the owners support and only stored messages qualify`() {
        val both = BranchActionSupport(fork = true, edit = true)
        val prompt = availability(message(role = "user"), branching = both)
        assertTrue(prompt.canFork)
        assertTrue(prompt.canEdit)

        val reply = availability(message(role = "assistant"), branching = both)
        assertTrue(reply.canFork)
        assertFalse(reply.canEdit, "only the user's own prompt is edited")

        assertFalse(availability(message(role = "user")).canFork, "an owner without forks hides the action")
        assertFalse(availability(message(role = "user").copy(isPending = true), branching = both).canFork)
        assertFalse(availability(message(role = "user").copy(isSendFailed = true), branching = both).canEdit)
    }

    @Test
    fun `a prompt with images is edited and rerun with them, unless only a pointer was kept`() {
        val support = BranchActionSupport(fork = true, edit = true, rerun = true)
        val withImage = message(role = "user").copy(
            attachments = listOf(UiImageAttachment(base64 = "image", mediaType = "image/png")),
        )
        val pointerOnly = message(role = "user").copy(
            attachments = listOf(UiImageAttachment(base64 = "", mediaType = "image/png", storedByteSize = 4)),
        )

        assertTrue(availability(withImage, branching = support).canEdit)
        assertTrue(availability(withImage, branching = support).canRerun)
        assertFalse(availability(pointerOnly, branching = support).canEdit)
        assertFalse(availability(pointerOnly, branching = support).canRerun)
        assertTrue(availability(pointerOnly, branching = support).canFork)
    }

    @Test
    fun `rerun is offered on prompts only, and only when the owner can rerun in a fork`() {
        val support = BranchActionSupport(fork = true, edit = true, rerun = true)
        assertTrue(availability(message(role = "user"), branching = support).canRerun)
        assertFalse(availability(message(role = "assistant"), branching = support).canRerun)
        assertFalse(availability(message(role = "user"), branching = support.copy(rerun = false)).canRerun)
    }

    private fun availability(
        message: UiMessage,
        sendAgainAvailable: Boolean = true,
        branching: BranchActionSupport = BranchActionSupport.None,
    ) = messageActionAvailability(
        message = message,
        copyText = "Structured message text",
        sendAgainAvailable = sendAgainAvailable,
        branching = branching,
    )

    private fun message(role: String) = UiMessage(
        id = "message-id",
        role = role,
        content = "Hello",
        timestamp = "2026-07-25T19:30:00Z",
    )
}
