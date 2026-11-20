package com.letta.mobile.data.media

/**
 * What an attachment is, for choosing its renderer and player. Derived from the MIME type first and
 * the file extension of its name or URL path second, so one detection rule serves every message
 * part, tool return and stored snapshot.
 */
enum class MediaKind {
    /** Drawn by the timeline's image grid and viewer. */
    Image,

    /** Played by the shared audio card. */
    Audio,

    /** Played by the shared video card. */
    Video,

    /** Anything else: a card that opens it elsewhere. */
    File,
    ;

    /** The MIME type a part of this kind gets when its producer named none. */
    val unknownMimeType: String
        get() = when (this) {
            Image -> "image/unknown"
            Audio -> "audio/unknown"
            Video -> "video/unknown"
            File -> "application/octet-stream"
        }

    companion object {
        /**
         * The kind of a part with [mimeType], falling back to the extension in [nameOrUrl] when the
         * type is blank or uninformative (`image/unknown`, `application/octet-stream`). A URL's query
         * and fragment are never read. Inline bytes with an uninformative type, and an `image/` type
         * with no telling extension, stay [Image]: only image parts carry inline bytes without a
         * specific type (audio and video parts take their part type's), as before audio and video existed.
         */
        fun of(mimeType: String?, nameOrUrl: String? = null): MediaKind {
            val type = mimeType?.trim()?.lowercase().orEmpty()
            return byMimeType(type) ?: nameOrUrl?.let(::byExtension) ?: fallback(type, nameOrUrl)
        }

        private fun byExtension(nameOrUrl: String): MediaKind? = extensionOf(nameOrUrl)?.let(EXTENSION_KINDS::get)

        private fun fallback(type: String, nameOrUrl: String?): MediaKind = when {
            nameOrUrl == null -> Image
            type.startsWith("image/") -> Image
            else -> File
        }

        /** Whether [mimeType] says nothing about the kind, so a caller may substitute what it knows. */
        fun isUninformative(mimeType: String?): Boolean = mimeType.isNullOrBlank() || mimeType.trim().lowercase() in UNINFORMATIVE_TYPES

        private fun byMimeType(type: String): MediaKind? = when {
            type in UNINFORMATIVE_TYPES -> null
            type.startsWith("image/") -> Image
            type.startsWith("audio/") -> Audio
            type.startsWith("video/") -> Video
            type.isEmpty() -> null
            else -> File
        }

        /** The lower-case extension of the last path segment of [nameOrUrl], without query or fragment. */
        internal fun extensionOf(nameOrUrl: String): String? {
            val path = nameOrUrl.substringBefore('?').substringBefore('#')
            val segment = path.substringAfterLast('/')
            val dot = segment.lastIndexOf('.')
            if (dot <= 0 || dot == segment.lastIndex) return null
            return segment.substring(dot + 1).lowercase()
        }

        private val UNINFORMATIVE_TYPES = setOf("image/unknown", "application/octet-stream", "binary/octet-stream")

        private val EXTENSION_KINDS: Map<String, MediaKind> = buildMap {
            listOf("png", "jpg", "jpeg", "webp", "gif", "bmp").forEach { put(it, Image) }
            listOf("mp3", "wav", "ogg", "oga", "opus", "m4a", "aac", "flac", "aif", "aiff", "au").forEach { put(it, Audio) }
            listOf("mp4", "m4v", "webm", "mov", "mkv").forEach { put(it, Video) }
        }
    }
}
