package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.data.attachment.ImageIngressPolicy
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.composer_attach_too_large
import org.jetbrains.compose.resources.getString

/**
 * Paste and drop of images onto the composer, where the composer itself has
 * to receive them: Android's rich-content receiver (Gboard, drag from another app) and Ctrl/Cmd+V,
 * and the browser's paste and drop events. The result goes on the composer panel. Null on desktop,
 * whose window already installs AWT paste and drop.
 *
 * Each image is handed to the [ComposerImageAttacher] as a [ComposerImageSource] that reads its
 * bytes when its turn comes, off the main thread, so a large drop never blocks the UI.
 */
internal expect val platformComposerImageIngress: (@Composable (ComposerImageAttacher) -> Modifier)?

/**
 * The whole stream, read in chunks through [read] (a platform stream's `read(buffer)`: bytes read,
 * or -1 at the end), or null as soon as it passes [limit] bytes, so an oversized source is refused
 * without ever being held whole.
 */
internal fun readAtMost(limit: Int, read: (ByteArray) -> Int): ByteArray? {
    val chunks = mutableListOf<ByteArray>()
    var total = 0
    val buffer = ByteArray(ReadChunkBytes)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (total + count > limit) return null
        chunks += buffer.copyOf(count)
        total += count
    }
    val out = ByteArray(total)
    var offset = 0
    chunks.forEach { chunk ->
        chunk.copyInto(out, offset)
        offset += chunk.size
    }
    return out
}

/**
 * A pasted or dropped image's bytes, read in chunks through [read]. Refused with the shared
 * "larger than N MB" reason when its [knownSize] (a file's size, when the platform has one) already
 * passes [ImageIngressPolicy.MAX_SOURCE_BYTES], before a byte is read, or as soon as the bytes read
 * pass it (a source that grew, or one with no known size).
 */
suspend fun readImageSource(knownSize: Long?, read: (ByteArray) -> Int): ByteArray {
    if (knownSize != null && knownSize > ImageIngressPolicy.MAX_SOURCE_BYTES) imageTooLarge()
    return readAtMost(ImageIngressPolicy.MAX_SOURCE_BYTES, read) ?: imageTooLarge()
}

/** Refuses a pasted or dropped image larger than [ImageIngressPolicy.MAX_SOURCE_BYTES]. */
internal suspend fun imageTooLarge(): Nothing =
    throw IllegalArgumentException(getString(Res.string.composer_attach_too_large, ImageIngressPolicy.MAX_SOURCE_BYTES / BytesPerMib))

private const val ReadChunkBytes = 64 * 1024
private const val BytesPerMib = 1024 * 1024
