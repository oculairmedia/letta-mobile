package com.letta.mobile.desktop.chat

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.attachment.ImageIngressPolicy
import com.letta.mobile.desktop.DesktopImageIngressSink
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopImageAttachmentLoaderTest {
    @Test
    fun loadDownscalesAndEncodesAsJpegWithinSharedLimits() = runTest {
        val path = Files.createTempFile("desktop-attachment", ".png")
        val source = BufferedImage(96, 48, BufferedImage.TYPE_INT_RGB)
        val graphics = source.createGraphics()
        try {
            graphics.color = Color.RED
            graphics.fillRect(0, 0, source.width, source.height)
        } finally {
            graphics.dispose()
        }
        ImageIO.write(source, "png", path.toFile())

        val limits = AttachmentLimits(
            maxAttachmentCount = 4,
            maxLongestEdgePx = 32,
            maxRawBytesPerImage = 32 * 1024,
            maxTotalBase64Bytes = 128 * 1024,
        )
        val image = assertNotNull(DesktopImageAttachmentLoader(limits).load(path))
        val bytes = Base64.getDecoder().decode(image.base64)
        val decoded = ImageIO.read(bytes.inputStream())

        assertEquals("image/jpeg", image.mediaType)
        assertTrue(bytes.size <= limits.maxRawBytesPerImage)
        assertEquals(32, maxOf(decoded.width, decoded.height))
    }

    @Test
    fun aFileOverTheSharedSourceCapIsRefusedBeforeDecode() = runTest {
        // letta-mobile-k5nbq.18: sparse, so the test never writes 25 MB.
        val path = Files.createTempFile("desktop-attachment-huge", ".png")
        try {
            RandomAccessFile(path.toFile(), "rw").use { it.setLength(ImageIngressPolicy.MAX_SOURCE_BYTES + 1L) }
            val error = assertFailsWith<IllegalArgumentException> { DesktopImageAttachmentLoader().load(path) }
            assertEquals("larger than 25 MB", error.message)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun aFailedDropIsReportedWithTheComposersSharedPrefix() = runBlocking {
        val path = Files.createTempFile("desktop-attachment-text", ".png")
        try {
            Files.writeString(path, "not an image")
            val errors = mutableListOf<String>()
            coroutineScope {
                DesktopImageIngressSink(this, DesktopImageAttachmentLoader(), onImage = {}, onError = { errors += it })
                    .launchLoad { load(path) }
            }
            assertEquals(1, errors.size)
            assertTrue(errors.single().startsWith("Could not attach that image: "), errors.single())
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
