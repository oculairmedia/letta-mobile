package com.letta.mobile.data.attachment

/**
 * Attachable-image policy shared by every ingress surface (drop, paste,
 * picker) so caps and formats never drift between platforms or hint text.
 */
object ImageIngressPolicy {
    /** Ingest cap per gesture, matching the composer's attachment grid. */
    const val MAX_FILES = 4

    /**
     * Most bytes one pasted or dropped image may have before it is decoded. A clipboard URI or a
     * dropped file can be any size (a long GIF, a camera RAW); past this it is refused unread, not
     * held whole in memory and handed to the decoder.
     */
    const val MAX_SOURCE_BYTES = 25 * 1024 * 1024

    val SUPPORTED_EXTENSIONS: Set<String> =
        setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

    fun isSupportedExtension(extension: String): Boolean =
        extension.lowercase() in SUPPORTED_EXTENSIONS

    /** Human-readable formats list for hint copy, derived from the policy. */
    fun supportedFormatsLabel(): String = SUPPORTED_EXTENSIONS.joinToString(", ")
}
