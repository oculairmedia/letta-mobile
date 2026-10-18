package io.ak1.drawbox

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ImageBitmapCachePressureTest {
    private val png = byteArrayOf(1, 2, 3, 4)

    @Test
    fun pressureDropsTheImagesNotDrawnThisFrameAndKeepsTheOnesOnScreen() {
        runBlocking {
            val cache = ImageBitmapCache(this, decode = { _, w, h -> StubBitmap(w, h) })
            cache.beginFrame()
            cache.bitmapFor("on-screen", png, targetDim = 64)
            cache.bitmapFor("scrolled-away", png, targetDim = 64)
            withTimeout(10_000) { while (cache.landed.longValue < 2) delay(10) }

            cache.beginFrame()
            assertNotNull(cache.bitmapFor("on-screen", png, targetDim = 64))
            cache.dropIdle()

            assertEquals(1, cache.size())
            assertNotNull(cache.bitmapFor("on-screen", png, targetDim = 64), "the image on screen was dropped")
        }
    }

    private class StubBitmap(override val width: Int, override val height: Int) : ImageBitmap {
        override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888
        override val colorSpace: ColorSpace = ColorSpaces.Srgb
        override val hasAlpha: Boolean = true
        override fun readPixels(
            buffer: IntArray,
            startX: Int,
            startY: Int,
            width: Int,
            height: Int,
            bufferOffset: Int,
            stride: Int,
        ) = Unit
        override fun prepareToDraw() = Unit
    }
}
