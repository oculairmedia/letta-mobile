package com.letta.mobile.ui.chat.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import com.letta.mobile.ui.image.decodeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64

/** Identifies an image payload by its size and content hash, without retaining the payload. */
internal data class ImageContentKey(val length: Int, val hash: Int) {
    companion object {
        fun of(base64: String) = ImageContentKey(base64.length, base64.hashCode())
    }
}

/**
 * A small least-recently-used cache. Not thread safe: the page reads and writes it only from
 * composition and its effects, which run on the UI thread.
 */
internal class ImageBitmapLruCache(private val maxEntries: Int) {
    private val entries = LinkedHashMap<ImageContentKey, ImageBitmap>()

    val size: Int get() = entries.size

    operator fun get(key: ImageContentKey): ImageBitmap? {
        val hit = entries.remove(key) ?: return null
        entries[key] = hit
        return hit
    }

    fun put(key: ImageContentKey, bitmap: ImageBitmap) {
        entries.remove(key)
        entries[key] = bitmap
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
    }
}

/** The page's decoded images, shared by rows, the viewer and the composer's thumbnails. */
internal object ChatImageBitmaps {
    private const val MAX_ENTRIES = 32

    val cache = ImageBitmapLruCache(MAX_ENTRIES)

    /** Decodes a base64 (MIME tolerant) image, or null when it is not one. */
    fun decode(base64: String): ImageBitmap? = runCatching { decodeImageBitmap(Base64.Mime.decode(base64)) }.getOrNull()
}

/**
 * letta-mobile-bglj6.1: [base64] as an image, decoded off the UI thread and cached, so scrolling
 * a row back into view shows it at once instead of decoding it again. Null while decoding, for a
 * blank payload, or when it is not an image.
 */
@Composable
internal fun rememberDecodedImage(base64: String): ImageBitmap? {
    val key = remember(base64) { ImageContentKey.of(base64) }
    val cached = remember(key) { if (base64.isBlank()) null else ChatImageBitmaps.cache[key] }
    val state = produceState(cached, key) {
        if (value != null || base64.isBlank()) return@produceState
        val decoded = withContext(Dispatchers.Default) { ChatImageBitmaps.decode(base64) }
        if (decoded != null) ChatImageBitmaps.cache.put(key, decoded)
        value = decoded
    }
    return state.value
}
