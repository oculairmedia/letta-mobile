package com.letta.mobile.ui.chat.surface.composer

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.model.MessageContentPart
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** letta-mobile-k5nbq.7/.15: paste/drop bytes are read capped, then use the picker's encode path. */
class ComposerImageIngressTest {
    @Test
    fun aSourceWithinTheCapIsReadWhole() {
        val bytes = ByteArray(200_000) { it.toByte() }
        assertContentEquals(bytes, readAtMost(bytes.size, ByteArrayInputStream(bytes)::read))
    }

    @Test
    fun aSourcePastTheCapIsRefusedWithoutReadingItAll() {
        val stream = ByteArrayInputStream(ByteArray(1_000_000))
        assertNull(readAtMost(100_000, stream::read))
        assertTrue(stream.available() > 0, "reading stops once the cap is passed")
    }

    @Test
    fun anOversizedSourceIsReportedAndNothingAttaches() = runBlocking {
        val attached = mutableListOf<MessageContentPart.Image>()
        val errors = mutableListOf<String>()
        val attacher = ComposerImageAttacher(this)
        attacher.bind(
            ComposerImagePickerTarget(
                maxAttachments = 4,
                pendingCount = 0,
                limits = AttachmentLimits.Default,
                onPicked = { attached += it },
                onError = { errors += it },
            ),
        )
        assertNotNull(attacher.attach(listOf<ComposerImageSource>({ imageTooLarge() }))).join()
        assertTrue(attached.isEmpty())
        assertEquals(listOf("Could not attach that image: larger than 25 MB"), errors)
    }

    @Test
    fun encodeAttachmentDownscalesToTheSharedEdgeCap() {
        val png = ByteArrayOutputStream().also { out ->
            val source = BufferedImage(96, 48, BufferedImage.TYPE_INT_RGB)
            val graphics = source.createGraphics()
            try {
                graphics.color = Color.RED
                graphics.fillRect(0, 0, source.width, source.height)
            } finally {
                graphics.dispose()
            }
            ImageIO.write(source, "png", out)
        }.toByteArray()
        val limits = AttachmentLimits(maxLongestEdgePx = 32, maxRawBytesPerImage = 32 * 1024)
        val image = encodeAttachment(png, limits)
        assertNotNull(image)
        val bytes = Base64.Default.decode(image.base64)
        val decoded = ImageIO.read(bytes.inputStream())
        assertEquals(32, maxOf(decoded.width, decoded.height))
        assertTrue(bytes.size <= limits.maxRawBytesPerImage)
    }
}
