package com.letta.mobile.data.canvas

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.model.MessageContentPart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Thrown when an exported canvas payload exceeds the maximum raw image byte limit.
 */
class CanvasAttachmentTooLargeException(message: String) : IllegalArgumentException(message)

/**
 * Utilities for sharing canvas exports to chat conversations as attachment bubbles.
 */
object CanvasShare {

    private val stagingMutex = Mutex()
    private val pendingAttachmentsByConversation = mutableMapOf<String, MutableList<MessageContentPart.Image>>()
    private val _stagedAttachmentEvents = MutableSharedFlow<Pair<String, MessageContentPart.Image>>(
        extraBufferCapacity = 32,
    )

    /**
     * Flow of staged canvas attachments emitted for conversations: `(conversationId, image)`.
     */
    val stagedAttachmentEvents: Flow<Pair<String, MessageContentPart.Image>> = _stagedAttachmentEvents.asSharedFlow()

    /**
     * Stages a packaged canvas attachment for the specified [conversationId].
     */
    suspend fun stageForConversation(conversationId: String?, image: MessageContentPart.Image) {
        val convId = conversationId.orEmpty()
        stagingMutex.withLock {
            pendingAttachmentsByConversation.getOrPut(convId) { mutableListOf() }.add(image)
        }
        _stagedAttachmentEvents.tryEmit(convId to image)
    }

    /**
     * Consumes and clears any staged attachments for [conversationId].
     */
    suspend fun consumeStagedAttachments(conversationId: String?): List<MessageContentPart.Image> {
        val convId = conversationId.orEmpty()
        return stagingMutex.withLock {
            pendingAttachmentsByConversation.remove(convId)?.toList() ?: emptyList()
        }
    }

    /**
     * Clears all pending staged attachments across all conversations.
     */
    suspend fun clearStagedAttachments() {
        stagingMutex.withLock {
            pendingAttachmentsByConversation.clear()
        }
    }

    /**
     * Sniffs the MIME type of a byte buffer based on standard magic numbers and tags.
     * Prefers raster formats (image/png, image/jpeg) when present; falls back to SVG or PNG.
     */
    fun detectMimeType(bytes: ByteArray): String {
        if (isPng(bytes)) return "image/png"
        if (isJpeg(bytes)) return "image/jpeg"
        if (isSvg(bytes)) return "image/svg+xml"
        return "image/png"
    }

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

    private fun isSvg(bytes: ByteArray): Boolean {
        val prefix = bytes.take(128).toByteArray().decodeToString()
        return prefix.contains("<svg", ignoreCase = true) || prefix.contains("<?xml", ignoreCase = true)
    }

    /**
     * Packages exported canvas bytes (PNG, JPEG, or SVG) into a [MessageContentPart.Image]
     * ready for chat staging, enforcing [AttachmentLimits].
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun createChatImageAttachment(
        bytes: ByteArray,
        mimeType: String = detectMimeType(bytes),
        limits: AttachmentLimits = AttachmentLimits.Default,
    ): MessageContentPart.Image {
        if (bytes.size > limits.maxRawBytesPerImage) {
            throw CanvasAttachmentTooLargeException(
                "Exported canvas payload (${bytes.size} bytes) exceeds maxRawBytesPerImage limit (${limits.maxRawBytesPerImage} bytes)"
            )
        }
        val base64 = Base64.encode(bytes)
        return MessageContentPart.Image(
            base64 = base64,
            mediaType = mimeType,
            storedByteSize = bytes.size.toLong(),
        )
    }

    /**
     * Alias for [createChatImageAttachment] matching the documented API contract.
     */
    fun prepareAttachment(
        bytes: ByteArray,
        mimeType: String = detectMimeType(bytes),
        suggestedFileName: String? = null,
        limits: AttachmentLimits = AttachmentLimits.Default,
    ): MessageContentPart.Image = createChatImageAttachment(bytes, mimeType, limits)

    /**
     * Convenience helper packaging canvas bytes into a [Result].
     */
    fun packageForChat(
        bytes: ByteArray,
        mimeType: String = detectMimeType(bytes),
        limits: AttachmentLimits = AttachmentLimits.Default,
    ): Result<MessageContentPart.Image> =
        runCatching { createChatImageAttachment(bytes, mimeType, limits) }
}
