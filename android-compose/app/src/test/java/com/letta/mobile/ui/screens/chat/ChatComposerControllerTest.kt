package com.letta.mobile.ui.screens.chat

import com.letta.mobile.data.model.MessageContentPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatComposerControllerTest {
    private val noTelemetry = ChatComposerTelemetry { _, _ -> }

    @Test
    fun `updateText updates state`() {
        val controller = ChatComposerController(telemetry = noTelemetry)

        controller.updateText("typing")

        assertEquals("typing", controller.state.value.inputText)
    }

    @Test
    fun `blank text and no attachments produces no payload`() {
        val controller = ChatComposerController(telemetry = noTelemetry)

        assertNull(controller.payloadForSend("   "))
    }

    @Test
    fun `blank text with attachment produces payload`() {
        val controller = ChatComposerController(telemetry = noTelemetry)
        val image = image(base64 = "ZmFrZQ==")
        assertTrue(controller.addAttachment(image))

        val payload = controller.payloadForSend("")

        assertEquals("", payload?.text)
        assertEquals(listOf(image), payload?.attachments)
    }

    @Test
    fun `nonblank text produces payload`() {
        val controller = ChatComposerController(telemetry = noTelemetry)

        val payload = controller.payloadForSend("hello")

        assertEquals("hello", payload?.text)
        assertEquals(emptyList<MessageContentPart.Image>(), payload?.attachments)
    }

    @Test
    fun `clearAfterSend clears text attachments and error`() {
        val controller = ChatComposerController(telemetry = noTelemetry)
        controller.updateText("hello")
        controller.addAttachment(image())
        controller.setError("bad")

        controller.clearAfterSend()

        assertEquals("", controller.state.value.inputText)
        assertTrue(controller.state.value.pendingAttachments.isEmpty())
        assertNull(controller.state.value.error)
    }

    @Test
    fun `clearAfterSend records trimmed input history newest first`() {
        val controller = ChatComposerController(telemetry = noTelemetry)

        controller.updateText(" first prompt ")
        controller.clearAfterSend()
        controller.updateText("second prompt")
        controller.clearAfterSend()

        assertEquals(listOf("second prompt", "first prompt"), controller.state.value.inputHistory)
    }

    @Test
    fun `clearAfterSend dedupes existing input history item`() {
        val controller = ChatComposerController(telemetry = noTelemetry)

        controller.updateText("repeat")
        controller.clearAfterSend()
        controller.updateText("other")
        controller.clearAfterSend()
        controller.updateText("repeat")
        controller.clearAfterSend()

        assertEquals(listOf("repeat", "other"), controller.state.value.inputHistory)
    }

    @Test
    fun `attachment count cap rejects fifth image`() {
        val controller = ChatComposerController(telemetry = noTelemetry)
        repeat(MAX_COMPOSER_ATTACHMENTS) { index ->
            assertTrue(controller.addAttachment(image(base64 = "image-$index")))
        }

        assertFalse(controller.addAttachment(image(base64 = "extra")))

        assertEquals(MAX_COMPOSER_ATTACHMENTS, controller.state.value.pendingAttachments.size)
        assertEquals(
            "Attachment limit reached ($MAX_COMPOSER_ATTACHMENTS max).",
            controller.state.value.error,
        )
    }

    @Test
    fun `attachment size cap rejects too-large total`() {
        val controller = ChatComposerController(telemetry = noTelemetry)

        assertFalse(controller.addAttachment(image(base64 = "x".repeat(MAX_COMPOSER_TOTAL_BYTES + 1))))

        assertTrue(controller.state.value.pendingAttachments.isEmpty())
        assertEquals(
            "Attachments too large — downscale or remove some before sending.",
            controller.state.value.error,
        )
    }

    @Test
    fun `removing invalid index is no-op`() {
        val controller = ChatComposerController(telemetry = noTelemetry)
        val image = image()
        controller.addAttachment(image)

        controller.removeAttachment(10)

        assertEquals(listOf(image), controller.state.value.pendingAttachments)
    }

    @Test
    fun `removing valid index updates attachments`() {
        val controller = ChatComposerController(telemetry = noTelemetry)
        val first = image(base64 = "first")
        val second = image(base64 = "second")
        controller.addAttachment(first)
        controller.addAttachment(second)

        controller.removeAttachment(0)

        assertEquals(listOf(second), controller.state.value.pendingAttachments)
    }

    @Test
    fun `adding valid attachment clears existing error`() {
        val controller = ChatComposerController(telemetry = noTelemetry)
        controller.setError("previous")

        assertTrue(controller.addAttachment(image()))

        assertNull(controller.state.value.error)
    }

    private fun image(
        base64: String = "ZmFrZQ==",
        mediaType: String = "image/png",
    ) = MessageContentPart.Image(
        base64 = base64,
        mediaType = mediaType,
    )
}
