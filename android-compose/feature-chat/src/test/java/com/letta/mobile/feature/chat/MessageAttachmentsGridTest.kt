package com.letta.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageAttachmentsGridTest {

    @Test
    fun `chat attachment image data url preserves raw base64 payload`() {
        val payload = "abc+/=="

        val dataUrl = chatAttachmentImageDataUrl(
            base64 = payload,
            mediaType = "image/jpeg",
        )

        assertEquals("data:image/jpeg;base64,abc+/==", dataUrl)
    }

    @Test
    fun `chat attachment cache key differentiates media type and payload`() {
        val pngKey = chatAttachmentImageCacheKey(
            base64 = "abc+/==",
            mediaType = "image/png",
        )
        val jpegKey = chatAttachmentImageCacheKey(
            base64 = "abc+/==",
            mediaType = "image/jpeg",
        )
        val otherPayloadKey = chatAttachmentImageCacheKey(
            base64 = "def+/==",
            mediaType = "image/png",
        )

        assertTrue(pngKey.startsWith("chat-attachment-image:image/png:"))
        assertNotEquals(pngKey, jpegKey)
        assertNotEquals(pngKey, otherPayloadKey)
    }
}
