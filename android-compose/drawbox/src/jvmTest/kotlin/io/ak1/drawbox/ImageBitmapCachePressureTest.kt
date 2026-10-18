package io.ak1.drawbox

import androidx.compose.ui.graphics.ImageBitmap
import java.lang.reflect.Proxy
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
            val cache = ImageBitmapCache(this, decode = { _, w, h -> fakeBitmap(w, h) })
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

    private fun fakeBitmap(width: Int, height: Int): ImageBitmap =
        Proxy.newProxyInstance(
            ImageBitmap::class.java.classLoader,
            arrayOf(ImageBitmap::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getWidth" -> width
                "getHeight" -> height
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "FakeImageBitmap(${width}x$height)"
                else -> null
            }
        } as ImageBitmap
}
