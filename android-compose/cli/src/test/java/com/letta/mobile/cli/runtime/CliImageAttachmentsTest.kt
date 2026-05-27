package com.letta.mobile.cli.runtime

import com.github.ajalt.clikt.core.UsageError
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CliImageAttachmentsTest {
    @Test
    fun `reads image data URLs as raw Letta image parts`() {
        val image = readImageAttachment("data:image/png;base64,QUJDRA==")

        assertEquals("image/png", image.mediaType)
        assertEquals("QUJDRA==", image.base64)
    }

    @Test
    fun `reads image files as base64 image parts`() {
        val file = Files.createTempFile("letta-cli-image", ".png")
        try {
            Files.write(file, byteArrayOf(1, 2, 3, 4))

            val image = readImageAttachment(file.toString())

            assertEquals("image/png", image.mediaType)
            assertEquals("AQIDBA==", image.base64)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `rejects non image files`() {
        val file = Files.createTempFile("letta-cli-image", ".txt")
        try {
            Files.write(file, "not an image".toByteArray(Charsets.UTF_8))

            assertThrows(UsageError::class.java) {
                readImageAttachment(file.toString())
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `rejects non base64 data URLs`() {
        assertThrows(UsageError::class.java) {
            readImageAttachment("data:image/png,not-base64")
        }
    }
}
