package com.letta.mobile.data.canvas

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.model.MessageContentPart
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Utilities for sharing canvas exports to chat conversations as attachment bubbles.
 */
object CanvasShare {

    /**
     * Packages exported canvas bytes (SVG or PNG) into a [MessageContentPart.Image]
     * ready for chat staging, enforcing [AttachmentLimits].
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun createChatImageAttachment(
        bytes: ByteArray,
        mimeType: String = "image/svg+xml",
        limits: AttachmentLimits = AttachmentLimits.Default,
    ): MessageContentPart.Image {
        require(bytes.size <= limits.maxRawBytesPerImage) {
            "Exported canvas payload (${bytes.size} bytes) exceeds maxRawBytesPerImage limit (${limits.maxRawBytesPerImage} bytes)"
        }
        val base64 = Base64.encode(bytes)
        return MessageContentPart.Image(
            base64 = base64,
            mediaType = mimeType,
            storedByteSize = bytes.size.toLong(),
        )
    }

    fun packageForChat(bytes: ByteArray, mimeType: String = "image/svg+xml"): Result<MessageContentPart.Image> =
        runCatching { createChatImageAttachment(bytes, mimeType) }
}
