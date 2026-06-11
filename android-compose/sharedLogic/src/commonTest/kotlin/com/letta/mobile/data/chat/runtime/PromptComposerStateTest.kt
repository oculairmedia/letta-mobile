package com.letta.mobile.data.chat.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptComposerStateTest {
    @Test
    fun blankReadyStateCannotSend() {
        val state = PromptComposerState()

        assertTrue(state.ready)
        assertFalse(state.isSending)
        assertFalse(state.isStreaming)
        assertFalse(state.canSend)
        assertFalse(state.canStop)
        assertNull(state.beginSend())
    }

    @Test
    fun textPayloadCanSendWhenReady() {
        val state = PromptComposerState().updateText(" hello ")

        assertTrue(state.canSend)
    }

    @Test
    fun sendingAndStreamingStatesCannotSendButCanStop() {
        val sending = PromptComposerState(text = "queued", status = PromptComposerStatus.Sending)
        val streaming = sending.beginStreaming()

        assertTrue(sending.isSending)
        assertFalse(sending.canSend)
        assertTrue(sending.canStop)
        assertTrue(streaming.isStreaming)
        assertFalse(streaming.canSend)
        assertTrue(streaming.canStop)
    }

    @Test
    fun stopReturnsSendingOrStreamingComposerToReady() {
        val sending = PromptComposerState(status = PromptComposerStatus.Sending)
        val streaming = PromptComposerState(status = PromptComposerStatus.Streaming)

        assertEquals(PromptComposerState(), sending.stop())
        assertEquals(PromptComposerState(), streaming.stop())
    }

    @Test
    fun beginSendBuildsPayloadAndResetsDraftFields() {
        val attachment = PromptComposerAttachment(
            id = "file-1",
            kind = "image",
            metadata = mapOf("mimeType" to "image/png"),
        )
        val template = PromptComposerTemplate(
            id = "template-1",
            label = "Summarize",
            variables = mapOf("tone" to "short"),
        )
        val model = PromptComposerModelMetadata(
            id = "model-1",
            label = "Fast model",
            route = "mobile/local",
        )
        val state = PromptComposerState(text = " draft ")
            .updateAttachments(listOf(attachment))
            .selectTemplate(template)
            .selectModel(model)

        val transition = assertNotNull(state.beginSend())

        assertEquals(
            PromptComposerOutgoingPayload(
                text = "draft",
                attachments = listOf(attachment),
                template = template,
                model = model,
            ),
            transition.payload,
        )
        assertEquals(PromptComposerState(status = PromptComposerStatus.Sending), transition.nextState)
        assertFalse(transition.nextState.canSend)
        assertTrue(transition.nextState.isSending)
    }

    @Test
    fun attachmentOrTemplatePayloadCanSendWithoutText() {
        assertTrue(
            PromptComposerState(
                attachments = listOf(PromptComposerAttachment(id = "file-1", kind = "image")),
            ).canSend,
        )
        assertTrue(PromptComposerState(template = PromptComposerTemplate(id = "template-1")).canSend)
    }
}
