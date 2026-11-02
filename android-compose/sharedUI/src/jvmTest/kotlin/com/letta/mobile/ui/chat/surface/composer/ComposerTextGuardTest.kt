package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposerTextGuardTest {
    @Test
    fun gifHeaderIsRejectedButReadableSignatureIsAccepted() {
        val previous = TextFieldValue("")
        // Byte-valued logical screen descriptor, including whitespace and a control byte.
        val descriptor = "\t\n\r \t\n\u001a"
        val whitespaceDescriptor = "\t\n\r \t\n\r"
        listOf("GIF87a", "GIF89a").forEach { signature ->
            assertTrue(hasGifHeader(signature + descriptor))
            assertTrue(isUnreadableComposerText(previous, signature + descriptor))
            assertFalse(hasGifHeader(signature + whitespaceDescriptor))
            assertFalse(isUnreadableComposerText(previous, signature + whitespaceDescriptor))
        }
        assertFalse(hasGifHeader("GIF89a"))
        assertFalse(hasGifHeader("GIF89a" + descriptor.withByte(0, 256)))
        assertFalse(hasGifHeader("GIF89a" + descriptor.withByte(0, 0).withByte(1, 0)))
        assertFalse(hasGifHeader("GIF89a" + descriptor.withByte(2, 0).withByte(3, 0)))
        assertFalse(isUnreadableComposerText(previous, "GIF89a is the GIF file signature"))
    }

    @Test
    fun gifDescriptorDistinguishesReadableLatin1FromBinaryEvidence() {
        val previous = TextFieldValue("")
        listOf(
            "GIF89a café is the GIF signature",
            "GIF87a naïve résumé of a format",
            "GIF89a ¡olé! is readable text",
            "GIF89a ÿþáé is readable text",
        ).forEach { text ->
            assertFalse(hasGifHeader(text), text)
            assertFalse(isUnreadableComposerText(previous, text), text)
        }
        val descriptor = "\u0001\u0000\u0001\u0000   "
        listOf('\u0000', '\u001a', '\u007f', '\u0080', '\u009f', '\ufffd').forEach { evidence ->
            val header = "GIF89a" + descriptor.replaceRange(4, 5, evidence.toString())
            assertTrue(hasGifHeader(header), "evidence U+${evidence.code.toString(16)}")
            assertTrue(isUnreadableComposerText(previous, header), "evidence U+${evidence.code.toString(16)}")
        }
        // Nonzero dimensions and otherwise printable bytes isolate the evidence predicate.
        listOf('\u0000', '\u0001', '\u001a', '\u007f', '\u0080', '\u009f', '\ufffd').forEach { evidence ->
            assertTrue(hasGifHeader("GIF89aabcd" + evidence + "ef"), "evidence U+${evidence.code.toString(16)}")
        }
        assertFalse(hasBmpHeader("BM café résumé with readable Latin-1 text"))
    }

    @Test
    fun readableDescriptionsOfOtherFormatsAreAccepted() {
        val previous = TextFieldValue("")
        listOf("PNG is an image format", "JPEG is an image format", "WEBP is an image format", "RIFF is a container format")
            .forEach { assertFalse(isUnreadableComposerText(previous, it), it) }
    }

    // BITMAPFILEHEADER + BITMAPINFOHEADER; 54-byte header and one plane.
    private val bmp = "BM" + "\u003a\u0000\u0000\u0000" + "\u0000\u0000\u0000\u0000" +
        "\u0036\u0000\u0000\u0000" + "\u0028\u0000\u0000\u0000" +
        "\u0001\u0000\u0000\u0000" + "\u0001\u0000\u0000\u0000" + "\u0001\u0000"

    private fun String.withByte(offset: Int, value: Int): String = replaceRange(offset, offset + 1, value.toChar().toString())

    @Test
    fun recognizesPlausibleBmpHeaderOnly() {
        assertTrue(hasBmpHeader(bmp))
        assertTrue(isUnreadableComposerText(TextFieldValue(""), bmp))
        assertFalse(hasBmpHeader("BM25 results"))
        assertFalse(hasBmpHeader(bmp.replaceRange(6, 7, "X")))
        assertFalse(hasBmpHeader(bmp.replaceRange(10, 11, "\u0001")))
        assertFalse(hasBmpHeader(bmp.replaceRange(26, 27, "\u0002")))
    }

    @Test
    fun validatesEachBmpHeaderField() {
        val invalidHeaders = listOf(
            "wrong signature" to bmp.replaceRange(0, 2, "XX"),
            "short file header" to bmp.take(25),
            "non-byte header" to bmp.withByte(3, 256),
            "reserved field" to bmp.withByte(9, 1),
            "unsupported DIB" to bmp.withByte(14, 41),
            "pixel offset before DIB end" to bmp.withByte(10, 53),
            "file size before pixel offset" to bmp.withByte(2, 53),
            "missing planes" to bmp.take(27),
            "non-byte planes" to bmp.withByte(27, 256),
            "wrong plane count" to bmp.withByte(26, 2),
        )
        invalidHeaders.forEach { (name, header) -> assertFalse(hasBmpHeader(header), name) }

        listOf(12, 40, 52, 56, 108, 124).forEach { size ->
            val offset = 14 + size
            val header = bmp.withByte(2, offset).withByte(10, offset).withByte(14, size)
            assertTrue(hasBmpHeader(header), "DIB size $size")
        }
    }
}
