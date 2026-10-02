package com.letta.mobile.ui.chat.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
 * A small least-recently-used cache, bounded by entry count AND by decoded bytes (an image costs
 * width x height x 4), so a few camera-sized images cannot pin hundreds of megabytes. Not thread
 * safe: the page reads and writes it only from composition and its effects, which run on the UI
 * thread (decoding itself happens off it).
 */
internal class ImageBitmapLruCache(private val maxEntries: Int, private val maxBytes: Long = Long.MAX_VALUE) {
    private val entries = LinkedHashMap<ImageContentKey, ImageBitmap>()

    val size: Int get() = entries.size

    /** The decoded bytes currently held. */
    var bytes: Long = 0L
        private set

    operator fun get(key: ImageContentKey): ImageBitmap? {
        val hit = entries.remove(key) ?: return null
        entries[key] = hit
        return hit
    }

    fun put(key: ImageContentKey, bitmap: ImageBitmap) {
        entries.remove(key)?.let { bytes -= it.byteCount() }
        // One image larger than the whole budget is not worth holding: it would evict everything.
        if (bitmap.byteCount() > maxBytes) return
        entries[key] = bitmap
        bytes += bitmap.byteCount()
        trim(maxBytes)
    }

    /** Evicts the least recently used images until at most [targetBytes] (and the entry cap) remain. */
    fun trim(targetBytes: Long) {
        while (entries.isNotEmpty() && overBudget(targetBytes)) {
            val eldest = entries.keys.first()
            entries.remove(eldest)?.let { bytes -= it.byteCount() }
        }
    }

    /** More entries than the cap, or more decoded bytes than [targetBytes]. */
    private fun overBudget(targetBytes: Long): Boolean {
        return entries.size > maxEntries || bytes > targetBytes
    }

    fun clear() {
        entries.clear()
        bytes = 0L
    }
}

/** What a decoded image costs in memory: four bytes per pixel. */
internal fun ImageBitmap.byteCount(): Long = width.toLong() * height.toLong() * BYTES_PER_PIXEL

private const val BYTES_PER_PIXEL = 4L

/** The page's decoded images, shared by rows, the viewer and the composer's thumbnails. */
internal object ChatImageBitmaps {
    private const val MAX_ENTRIES = 32
    private const val MAX_BYTES = 48L * 1024 * 1024

    val cache = ImageBitmapLruCache(MAX_ENTRIES, MAX_BYTES)

    /** Decodes a base64 (MIME tolerant) image, or null when it is not one. */
    fun decode(base64: String): ImageBitmap? = runCatching { decodeImageBitmap(Base64.Mime.decode(base64)) }.getOrNull()

    /** Releases memory under pressure, keeping at most [targetBytes] of the most recent images. */
    fun trim(targetBytes: Long = MAX_BYTES / 2) = cache.trim(targetBytes)

    fun clear() = cache.clear()
}

/**
 * Drops the decoded images when the page moves to another conversation: they belong to the one
 * it left. The first conversation the page opens keeps whatever is cached.
 */
@Composable
internal fun ReleaseImagesOnConversationChange(conversationId: String?) {
    val shown = remember { ShownConversation() }
    SideEffect {
        if (conversationId == null) return@SideEffect
        val previous = shown.id
        if (previous != null && previous != conversationId) ChatImageBitmaps.clear()
        shown.id = conversationId
    }
}

private class ShownConversation {
    var id: String? = null
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
