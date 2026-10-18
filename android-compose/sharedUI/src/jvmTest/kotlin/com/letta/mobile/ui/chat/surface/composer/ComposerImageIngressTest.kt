package com.letta.mobile.ui.chat.surface.composer

import com.letta.mobile.data.attachment.AttachmentLimits
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** letta-mobile-k5nbq.7: paste/drop bytes use the same encode path as the picker. */
class ComposerImageIngressTest {
    @Test
    fun pasteBytesBecomeOneSourceEach() {
        val sources = imageBytesAsSources(listOf(byteArrayOf(1), byteArrayOf(2, 3)))
        assertEquals(2, sources.size)
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
