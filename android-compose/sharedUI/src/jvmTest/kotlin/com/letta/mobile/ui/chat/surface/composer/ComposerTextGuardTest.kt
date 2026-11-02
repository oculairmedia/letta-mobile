package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposerTextGuardTest {
    @Test
    fun recognizesPlausibleBmpHeaderOnly() {
        // BITMAPFILEHEADER + BITMAPINFOHEADER; 54-byte header and one plane.
        val bmp = "BM" + "\u003a\u0000\u0000\u0000" + "\u0000\u0000\u0000\u0000" +
            "\u0036\u0000\u0000\u0000" + "\u0028\u0000\u0000\u0000" +
            "\u0001\u0000\u0000\u0000" + "\u0001\u0000\u0000\u0000" + "\u0001\u0000"
        assertTrue(hasBmpHeader(bmp))
        assertTrue(isUnreadableComposerText(TextFieldValue(""), bmp))
        assertFalse(hasBmpHeader("BM25 results"))
        assertFalse(hasBmpHeader(bmp.replaceRange(6, 7, "X")))
        assertFalse(hasBmpHeader(bmp.replaceRange(10, 11, "\u0001")))
        assertFalse(hasBmpHeader(bmp.replaceRange(26, 27, "\u0002")))
    }
}
