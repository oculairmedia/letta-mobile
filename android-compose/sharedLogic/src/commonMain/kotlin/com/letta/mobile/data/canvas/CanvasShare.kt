package com.letta.mobile.data.canvas

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.model.MessageContentPart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.jvm.JvmInline

/**
 * Thrown when an exported canvas payload exceeds the maximum raw image byte limit.
 */
class CanvasAttachmentTooLargeException(message: String) : IllegalArgumentException(message)

/**
 * Strongly typed conversation target for staged canvas attachments.
 */
@JvmInline
value class CanvasConversationTarget(val id: String = "") {
    companion object {
        val Unspecified = CanvasConversationTarget("")
        fun from(id: String?) = CanvasConversationTarget(id.orEmpty())
    }
}

/**
 * Recognized canvas export MIME types.
 */
enum class CanvasMimeType(val value: String) {
    PNG("image/png"),
    JPEG("image/jpeg"),
    SVG("image/svg+xml");

    companion object {
        fun fromValue(value: String): CanvasMimeType =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: PNG
    }
}

/**
 * Lifecycle-owned staging queue for shared canvas attachments.
 *
 * The pending queue is the single source of truth for what has been staged. The event flow only
 * says "something was staged for this target"; a consumer answers it by draining the queue with
 * [consumeStagedAttachments]. Carrying the image on the event as well would hand a live chat the
 * image twice: once from the event and once more when it next drains the queue at startup.
 */
class CanvasShareStaging {
    private val stagingMutex = Mutex()
    private val pendingAttachmentsByConversation = mutableMapOf<String, MutableList<MessageContentPart.Image>>()
    private val _stagedAttachmentEvents = MutableSharedFlow<CanvasConversationTarget>(extraBufferCapacity = 32)

    /**
     * Wake-up signals naming a conversation that has attachments waiting in the pending queue.
     */
    val stagedAttachmentEvents: SharedFlow<CanvasConversationTarget> = _stagedAttachmentEvents.asSharedFlow()

    /**
     * Stages a packaged canvas attachment for the specified [target].
     *
     * Suspends rather than drops if every subscriber's buffer is full, so a live chat never
     * misses the signal; with no subscriber the signal is simply not needed, because the next
     * consumer drains the queue on subscription.
     */
    suspend fun stageForConversation(target: CanvasConversationTarget, image: MessageContentPart.Image) {
        val convId = target.id
        stagingMutex.withLock {
            pendingAttachmentsByConversation.getOrPut(convId) { mutableListOf() }.add(image)
        }
        _stagedAttachmentEvents.emit(target)
    }

    /**
     * Consumes and clears any staged attachments for [target].
     */
    suspend fun consumeStagedAttachments(target: CanvasConversationTarget): List<MessageContentPart.Image> {
        val convId = target.id
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
}

/**
 * Utilities for sharing canvas exports to chat conversations as attachment bubbles.
 */
object CanvasShare {

    private val staging = CanvasShareStaging()

    /**
     * Wake-up signals naming a conversation that has attachments waiting; drain them with
     * [consumeStagedAttachments].
     */
    val stagedAttachmentEvents: SharedFlow<CanvasConversationTarget>
        get() = staging.stagedAttachmentEvents

    /**
     * Stages a packaged canvas attachment for the specified [target].
     */
    suspend fun stageForConversation(target: CanvasConversationTarget, image: MessageContentPart.Image) {
        staging.stageForConversation(target, image)
    }

    /**
     * Consumes and clears any staged attachments for [target].
     */
    suspend fun consumeStagedAttachments(target: CanvasConversationTarget): List<MessageContentPart.Image> =
        staging.consumeStagedAttachments(target)

    /**
     * Clears all pending staged attachments across all conversations.
     */
    suspend fun clearStagedAttachments() {
        staging.clearStagedAttachments()
    }

    /**
     * Sniffs the MIME type of a byte buffer based on standard magic numbers and tags.
     * Prefers raster formats (image/png, image/jpeg) when present; falls back to SVG or PNG.
     */
    fun detectMimeType(bytes: ByteArray): CanvasMimeType {
        if (isPng(bytes)) return CanvasMimeType.PNG
        if (isJpeg(bytes)) return CanvasMimeType.JPEG
        if (isSvg(bytes)) return CanvasMimeType.SVG
        return CanvasMimeType.PNG
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
        mimeType: CanvasMimeType = detectMimeType(bytes),
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
            mediaType = mimeType.value,
            storedByteSize = bytes.size.toLong(),
        )
    }

    /**
     * Alias for [createChatImageAttachment] matching the documented API contract.
     */
    fun prepareAttachment(
        bytes: ByteArray,
        mimeType: CanvasMimeType = detectMimeType(bytes),
        limits: AttachmentLimits = AttachmentLimits.Default,
    ): MessageContentPart.Image = createChatImageAttachment(bytes, mimeType, limits)

    /**
     * Convenience helper packaging canvas bytes into a [Result].
     */
    fun packageForChat(
        bytes: ByteArray,
        mimeType: CanvasMimeType = detectMimeType(bytes),
        limits: AttachmentLimits = AttachmentLimits.Default,
    ): Result<MessageContentPart.Image> =
        runCatching { createChatImageAttachment(bytes, mimeType, limits) }
}
