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
