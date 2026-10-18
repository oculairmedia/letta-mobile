package io.ak1.drawbox

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ImageBitmapCachePressureTest {
    private val png = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAADUlEQVR42mNk+M9QDwADjgGAv7SktwAAAABJRU5ErkJggg==",
    )

    @Test
    fun pressureDropsTheImagesNotDrawnThisFrameAndKeepsTheOnesOnScreen() = runBlocking {
        val cache = ImageBitmapCache(this)
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
