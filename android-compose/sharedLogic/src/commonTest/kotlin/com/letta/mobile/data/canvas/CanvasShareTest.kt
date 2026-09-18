package com.letta.mobile.data.canvas

import com.letta.mobile.data.attachment.AttachmentLimits
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

        val image = CanvasShare.createChatImageAttachment(bytes, CanvasMimeType.SVG)

        assertEquals("image/svg+xml", image.mediaType)
        assertEquals(bytes.size.toLong(), image.storedByteSize)
        val decoded = Base64.decode(image.base64).decodeToString()
        assertEquals(rawSvg, decoded)
    }

    @Test
    fun createChatImageAttachment_throwsWhenExceedingCap() {
        val limits = AttachmentLimits(maxRawBytesPerImage = 100)
        val oversized = ByteArray(101) { 1 }

        val error = assertFailsWith<CanvasAttachmentTooLargeException> {
            CanvasShare.createChatImageAttachment(oversized, CanvasMimeType.PNG, limits)
        }
        assertTrue(error.message!!.contains("exceeds maxRawBytesPerImage limit"))
    }

    @Test
    fun prepareAttachment_returnsAttachmentUsingSniffedMime() {
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        val attachment = CanvasShare.prepareAttachment(pngBytes)
        assertEquals("image/png", attachment.mediaType)
        assertEquals(8L, attachment.storedByteSize)
    }

    @Test
    fun detectMimeType_recognizesHeaders() {
        val png = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0, 0, 0, 0)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)
        val svg = "<svg viewBox='0 0 10 10'></svg>".encodeToByteArray()
        val xmlSvg = "<?xml version='1.0'?><svg></svg>".encodeToByteArray()

        assertEquals(CanvasMimeType.PNG, CanvasShare.detectMimeType(png))
        assertEquals(CanvasMimeType.JPEG, CanvasShare.detectMimeType(jpeg))
        assertEquals(CanvasMimeType.SVG, CanvasShare.detectMimeType(svg))
        assertEquals(CanvasMimeType.SVG, CanvasShare.detectMimeType(xmlSvg))
    }

    @Test
    fun stageAndConsume_scopedToConversationId() = runTest {
        CanvasShare.clearStagedAttachments()

        val target123 = CanvasConversationTarget("conv-123")
        val target456 = CanvasConversationTarget("conv-456")
        val imgA = CanvasShare.createChatImageAttachment("sample-A".encodeToByteArray(), CanvasMimeType.PNG)
        val imgB = CanvasShare.createChatImageAttachment("sample-B".encodeToByteArray(), CanvasMimeType.PNG)

        CanvasShare.stageForConversation(target123, imgA)
        CanvasShare.stageForConversation(target456, imgB)

        val consumed123 = CanvasShare.consumeStagedAttachments(target123)
        assertEquals(1, consumed123.size)
        assertEquals(imgA, consumed123.first())

        // Ensure 123 is cleared but 456 remains
        assertTrue(CanvasShare.consumeStagedAttachments(target123).isEmpty())
        val consumed456 = CanvasShare.consumeStagedAttachments(target456)
        assertEquals(1, consumed456.size)
        assertEquals(imgB, consumed456.first())
    }

    @Test
    fun stagingSignalsTheTargetAndTheQueueDeliversTheImageOnce() = runTest {
        CanvasShare.clearStagedAttachments()
        val target = CanvasConversationTarget("conv-signal")
        val image = CanvasShare.createChatImageAttachment("signal".encodeToByteArray(), CanvasMimeType.PNG)

        val signals = mutableListOf<CanvasConversationTarget>()
        val delivered = mutableListOf<com.letta.mobile.data.model.MessageContentPart.Image>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            CanvasShare.stagedAttachmentEvents.collect { signalled ->
                signals += signalled
                delivered += CanvasShare.consumeStagedAttachments(signalled)
            }
        }

        CanvasShare.stageForConversation(target, image)
        runCurrent()

        assertEquals(listOf(target), signals)
        assertEquals(listOf(image), delivered)
        // A later consumer (a chat screen starting up) finds nothing left to add.
        assertTrue(CanvasShare.consumeStagedAttachments(target).isEmpty())
        collector.cancel()
    }

    @Test
    fun aRefusedImageAndEverythingBehindItStayQueued() = runTest {
        CanvasShare.clearStagedAttachments()
        val target = CanvasConversationTarget("conv-cap")
        val images = (1..3).map { CanvasShare.createChatImageAttachment("img-$it".encodeToByteArray(), CanvasMimeType.PNG) }
        images.forEach { CanvasShare.stageForConversation(target, it) }

        // A composer with room for exactly one more attachment.
        var room = 1
        val taken = CanvasShare.consumeStagedAttachments(target) { if (room > 0) { room--; true } else false }
        assertEquals(listOf(images[0]), taken)

        // The refused second image is still first in line, and the third is behind it.
        assertEquals(listOf(images[1], images[2]), CanvasShare.consumeStagedAttachments(target))
        assertTrue(CanvasShare.consumeStagedAttachments(target).isEmpty())
    }

    @Test
    fun generatedCanvasIdsDoNotCollide() {
        val ids = (1..10_000).map { CanvasId.generate() }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.value.startsWith("canvas-") })
    }

    @Test
    fun packageForChat_withinLimits_succeeds() {
        val bytes = "valid-payload".encodeToByteArray()
        val result = CanvasShare.packageForChat(bytes)
        assertTrue(result.isSuccess)
        assertEquals(bytes.size.toLong(), result.getOrThrow().storedByteSize)
    }

    @Test
    fun packageForChat_exceedingLimit_fails() {
        val limits = AttachmentLimits(maxRawBytesPerImage = 5)
        val bytes = "too-large-payload".encodeToByteArray()
        val result = CanvasShare.packageForChat(bytes, limits = limits)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is CanvasAttachmentTooLargeException)
    }
}
