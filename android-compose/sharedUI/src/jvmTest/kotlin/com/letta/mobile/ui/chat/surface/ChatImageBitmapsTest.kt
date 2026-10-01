@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Images decode off the UI thread once and are then served from a bounded cache (R7). */
class ChatImageBitmapsTest {
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAADUlEQVR42mNk+M9QDwADjgGAv7SktwAAAABJRU5ErkJggg=="

    @Test
    fun lruEvictsTheLeastRecentlyUsed() {
        val bitmap = assertNotNull(ChatImageBitmaps.decode(png))
        val cache = ImageBitmapLruCache(maxEntries = 2)
        val a = ImageContentKey(1, 1)
        val b = ImageContentKey(2, 2)
        val c = ImageContentKey(3, 3)
        cache.put(a, bitmap)
        cache.put(b, bitmap)
        cache[a] // a is now the most recently used
        cache.put(c, bitmap)

        assertEquals(2, cache.size)
        assertNotNull(cache[a])
        assertNull(cache[b])
        assertNotNull(cache[c])
    }

    @Test
    fun theCacheIsBoundedByDecodedBytes() {
        val bitmap = assertNotNull(ChatImageBitmaps.decode(png)) // 2 x 1 px = 8 bytes
        val cache = ImageBitmapLruCache(maxEntries = 32, maxBytes = 20)
        cache.put(ImageContentKey(1, 1), bitmap)
        cache.put(ImageContentKey(2, 2), bitmap)
        assertEquals(16, cache.bytes)

        cache.put(ImageContentKey(3, 3), bitmap)

        assertEquals(2, cache.size)
        assertEquals(16, cache.bytes)
        assertNull(cache[ImageContentKey(1, 1)])
    }

    @Test
    fun anImageLargerThanTheBudgetIsNotKept() {
        val bitmap = assertNotNull(ChatImageBitmaps.decode(png))
        val cache = ImageBitmapLruCache(maxEntries = 32, maxBytes = 4)
        cache.put(ImageContentKey(1, 1), bitmap)
        assertEquals(0, cache.size)
        assertEquals(0, cache.bytes)
    }

    @Test
    fun trimAndClearReleaseMemory() {
        val bitmap = assertNotNull(ChatImageBitmaps.decode(png))
        val cache = ImageBitmapLruCache(maxEntries = 32)
        repeat(4) { cache.put(ImageContentKey(it, it), bitmap) }

        cache.trim(targetBytes = 16)
        assertEquals(2, cache.size)
        assertNotNull(cache[ImageContentKey(3, 3)])

        cache.clear()
        assertEquals(0, cache.size)
        assertEquals(0, cache.bytes)
    }

    @Test
    fun decodeRejectsWhatIsNotAnImage() {
        assertNull(ChatImageBitmaps.decode("not an image"))
        assertEquals(2, ChatImageBitmaps.decode(png)?.width)
    }

    @Test
    fun aDecodedImageIsServedFromTheCacheNextTime() = runComposeUiTest {
        var first: ImageBitmap? = null
        setContent { first = rememberDecodedImage(png) }
        waitUntil(timeoutMillis = 10_000) { first != null }

        assertSame(first, ChatImageBitmaps.cache[ImageContentKey.of(png)])
    }
}
