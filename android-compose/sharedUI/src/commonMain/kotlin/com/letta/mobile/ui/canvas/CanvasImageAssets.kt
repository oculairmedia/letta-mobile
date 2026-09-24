package com.letta.mobile.ui.canvas

import com.letta.mobile.data.storage.AssetStore
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.PayLoad

/**
 * A board's images kept in an [AssetStore] rather than only inside the drawing (letta-mobile-w3nb2.2).
 *
 * - [adopt]: an image that has bytes but no asset gets one — its bytes stored under their hash, a
 *   ref, its media type and a small preview. That is how newly placed images and the images of
 *   boards made before this come to live in the store.
 * - [hydrate]: an image that arrives with a ref but without its bytes (a drawing that no longer
 *   carries them) is given them from the store, or its preview until the store has them.
 * - [keep]: an image that arrives with both is kept in the store, so this device still has it
 *   once drawings stop carrying image bytes.
 *
 * All of it is disk and hashing work, so hosts run it off the main thread.
 */
internal object CanvasImageAssets {
    /** A preview is carried inline in the drawing, so it is kept small: the chat's inline budget. */
    const val MAX_PREVIEW_BYTES = 16 * 1024

    fun adopt(image: Element.Image, store: AssetStore): Element.Image {
        if (image.assetRef != null || image.bytes.isEmpty()) return image
        val mediaType = sniffMediaType(image.bytes)
        val asset = runCatching { store.put(mediaType, image.bytes) }.getOrNull() ?: return image
        return image.copy(assetRef = asset.ref, mediaType = mediaType, preview = image.preview ?: previewOf(image.bytes))
    }

    fun hydrate(image: Element.Image, store: AssetStore): Element.Image {
        val ref = image.assetRef ?: return image
        if (image.bytes.isNotEmpty()) return image
        val bytes = store.get(ref) ?: image.preview ?: return image
        return image.copy(bytes = bytes)
    }

    /** Keeps an image that came with its bytes in the store, when the bytes are really its asset's. */
    fun keep(image: Element.Image, store: AssetStore) {
        val ref = image.assetRef ?: return
        if (image.bytes.isEmpty() || store.has(ref)) return
        // Bytes that do not hash to the ref they came with land under their own ref, which nothing
        // points at: harmless, and never served for [ref], since the store is content-addressed.
        runCatching { store.put(image.mediaType ?: sniffMediaType(image.bytes), image.bytes) }
    }

    /** [hydrate] and [keep] for every image in a parsed drawing. */
    fun resolve(payLoad: PayLoad, store: AssetStore): PayLoad {
        if (payLoad.elements.none { it is Element.Image }) return payLoad
        return payLoad.copy(
            elements = payLoad.elements.map { element ->
                if (element is Element.Image) {
                    keep(element, store)
                    hydrate(element, store)
                } else {
                    element
                }
            },
        )
    }

    /** A thumbnail for the drawing to carry inline, or null when none fits [MAX_PREVIEW_BYTES]. */
    fun previewOf(bytes: ByteArray): ByteArray? {
        for (edge in PREVIEW_EDGES) {
            val preview = runCatching { prepareCanvasImage(bytes, edge) }.getOrNull() ?: return null
            if (preview.bytes.size <= MAX_PREVIEW_BYTES) return preview.bytes
        }
        return null
    }

    /** The media type of encoded image [bytes], from their signature. */
    fun sniffMediaType(bytes: ByteArray): String = when {
        bytes.startsWith(0x89, 0x50, 0x4E, 0x47) -> "image/png"
        bytes.startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
        bytes.startsWith(0x47, 0x49, 0x46, 0x38) -> "image/gif"
        bytes.size >= 12 && bytes.startsWith(0x52, 0x49, 0x46, 0x46) &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() -> "image/webp"
        bytes.startsWith(0x42, 0x4D) -> "image/bmp"
        else -> "application/octet-stream"
    }

    private fun ByteArray.startsWith(vararg signature: Int): Boolean =
        size >= signature.size && signature.indices.all { this[it] == signature[it].toByte() }

    /** Tried in turn until a preview fits; a busy screenshot needs the smaller edge. */
    private val PREVIEW_EDGES = listOf(160, 112, 72)
}
