package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.model.MessageContentPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatComposerPolicyTest {
    @Test
    fun textOrImageAttachmentMakesComposerSendable() {
        assertFalse(ChatComposerState().hasPayload)
        assertTrue(ChatComposerPolicy.updateText(ChatComposerState(), "hello").hasPayload)
        assertTrue(ChatComposerPolicy.attachImage(ChatComposerState(), image("AAAA")).hasPayload)
    }

    @Test
    fun attachImageEnforcesSharedCountLimit() {
        val limits = AttachmentLimits(maxAttachmentCount = 2)
        val withTwo = ChatComposerPolicy.attachImage(
            ChatComposerPolicy.attachImage(ChatComposerState(), image("1"), limits),
            image("2"),
            limits,
        )

        val rejected = ChatComposerPolicy.attachImage(withTwo, image("3"), limits)

        assertEquals(2, rejected.pendingImageAttachments.size)
        assertEquals(ChatComposerError.MaxAttachmentCountExceeded, rejected.error)
    }

    @Test
    fun attachImageEnforcesSharedTotalBase64Limit() {
        val limits = AttachmentLimits(maxTotalBase64Bytes = 5)
        val accepted = ChatComposerPolicy.attachImage(ChatComposerState(), image("1234"), limits)

        val rejected = ChatComposerPolicy.attachImage(accepted, image("56"), limits)

        assertEquals(1, rejected.pendingImageAttachments.size)
        assertEquals(ChatComposerError.MaxTotalBase64BytesExceeded, rejected.error)
    }

    @Test
    fun removeImageClearsError() {
        val state = ChatComposerState(
            pendingImageAttachments = listOf(image("1"), image("2")),
            error = ChatComposerError.MaxAttachmentCountExceeded,
        )

        val next = ChatComposerPolicy.removeImageAttachment(state, index = 0)

        assertEquals(listOf(image("2")), next.pendingImageAttachments)
        assertNull(next.error)
    }

    @Test
    fun beginSendReturnsTrimmedDraftAndClearedComposer() {
        val image = image("AAAA")
        val state = ChatComposerState(
            text = " describe ",
            pendingImageAttachments = listOf(image),
        )

        val draft = ChatComposerPolicy.beginSend(state)!!

        assertEquals("describe", draft.text)
        assertEquals(listOf(image), draft.attachments)
        assertEquals(ChatComposerState(), draft.nextState)
    }

    @Test
    fun beginSendReturnsNullWhenComposerIsEmpty() {
        assertNull(ChatComposerPolicy.beginSend(ChatComposerState(text = "   ")))
    }

    @Test
    fun sendFailureRestoresTextAndAttachmentsForRetry() {
        val image = image("AAAA")

        val restored = ChatComposerPolicy.restoreAfterSendFailure(
            text = "retry",
            attachments = listOf(image),
        )

        assertEquals("retry", restored.text)
        assertEquals(listOf(image), restored.pendingImageAttachments)
    }

    private fun image(base64: String): MessageContentPart.Image =
        MessageContentPart.Image(base64 = base64, mediaType = "image/png")
}
