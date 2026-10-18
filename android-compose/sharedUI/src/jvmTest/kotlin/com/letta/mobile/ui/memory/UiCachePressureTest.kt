package com.letta.mobile.ui.memory

import coil3.Canvas
import coil3.Image
import coil3.memory.MemoryCache
import com.letta.mobile.data.platform.MemoryPressureLevel
import com.letta.mobile.ui.chat.surface.ChatImageBitmaps
import com.letta.mobile.ui.chat.surface.ImageContentKey
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class UiCachePressureTest {
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAADUlEQVR42mNk+M9QDwADjgGAv7SktwAAAABJRU5ErkJggg=="

    @AfterTest
    fun emptyTheSharedCache() = ChatImageBitmaps.clear()

    @Test
    fun moderateHalvesTheImageLoaderCacheAndKeepsRecentChatImages() {
        val loader = loaderCacheHolding(4)
        ChatImageBitmaps.cache.put(ImageContentKey(1, 1), assertNotNull(ChatImageBitmaps.decode(png)))

        UiCachePressure.release(MemoryPressureLevel.MODERATE, loader)

        assertEquals(setOf(MemoryCache.Key("image-2"), MemoryCache.Key("image-3")), loader.keys)
        assertEquals(1, ChatImageBitmaps.cache.size, "a chat cache under half its budget keeps its images")
    }

    @Test
    fun criticalEmptiesBothImageCaches() {
        val loader = loaderCacheHolding(4)
        ChatImageBitmaps.cache.put(ImageContentKey(1, 1), assertNotNull(ChatImageBitmaps.decode(png)))

        UiCachePressure.release(MemoryPressureLevel.CRITICAL, loader)

        assertEquals(0L, loader.size)
        assertEquals(0, ChatImageBitmaps.cache.size)
    }

    @Test
    fun aHostWithoutAnImageLoaderCacheStillReleasesChatImages() {
        ChatImageBitmaps.cache.put(ImageContentKey(1, 1), assertNotNull(ChatImageBitmaps.decode(png)))

        UiCachePressure.release(MemoryPressureLevel.CRITICAL, images = null)

        assertEquals(0, ChatImageBitmaps.cache.size)
    }

    /** An image loader cache of 100 bytes holding [count] 20-byte images, oldest first. */
    private fun loaderCacheHolding(count: Int): MemoryCache {
        val cache = MemoryCache.Builder().maxSizeBytes(100).weakReferencesEnabled(false).build()
        repeat(count) { cache[MemoryCache.Key("image-$it")] = MemoryCache.Value(SizedImage(20)) }
        return cache
    }

    private class SizedImage(override val size: Long) : Image {
        override val width: Int = 1
        override val height: Int = 1
        override val shareable: Boolean = true
        override fun draw(canvas: Canvas) = Unit
    }
}
