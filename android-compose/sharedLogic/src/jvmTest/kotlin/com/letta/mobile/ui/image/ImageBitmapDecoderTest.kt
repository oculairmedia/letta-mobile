package com.letta.mobile.ui.image

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ImageBitmapDecoderTest {
    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun decodeImageBitmapReturnsImageWithExpectedDimensions() {
        val bytes = Base64.decode(TwoByOnePngBase64)

        val bitmap = assertNotNull(decodeImageBitmap(bytes))

        assertEquals(2, bitmap.width)
        assertEquals(1, bitmap.height)
    }

    private companion object {
        const val TwoByOnePngBase64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAADUlEQVR42mNk+M9QDwADjgGAv7SktwAAAABJRU5ErkJggg=="
    }
}
