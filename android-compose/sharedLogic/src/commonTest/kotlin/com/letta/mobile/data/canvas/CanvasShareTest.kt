package com.letta.mobile.data.canvas

import com.letta.mobile.data.attachment.AttachmentLimits
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CanvasShareTest {

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun createChatImageAttachment_encodesPayloadAndSetsMetadata() {
        val rawSvg = """<svg xmlns="http://www.w3.org/2000/svg"><rect width="100" height="100"/></svg>"""
        val bytes = rawSvg.encodeToByteArray()

        val image = CanvasShare.createChatImageAttachment(bytes, "image/svg+xml")

        assertEquals("image/svg+xml", image.mediaType)
        assertEquals(bytes.size.toLong(), image.storedByteSize)
        val decoded = Base64.decode(image.base64).decodeToString()
        assertEquals(rawSvg, decoded)
    }

    @Test
    fun createChatImageAttachment_throwsWhenExceedingCap() {
        val limits = AttachmentLimits(maxRawBytesPerImage = 100)
        val oversized = ByteArray(101) { 1 }

        val error = assertFailsWith<IllegalArgumentException> {
            CanvasShare.createChatImageAttachment(oversized, "image/png", limits)
        }
        assertTrue(error.message!!.contains("exceeds maxRawBytesPerImage limit"))
    }
}
