package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposerTextGuardTest {
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
