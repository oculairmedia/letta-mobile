package com.letta.mobile.data.media

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.attachment.ImageUnavailableReason

/**
 * One attachment as the timeline draws it: an image, audio, video or other file the agent or the
 * person shared. [kind] picks the renderer; images keep their thumbnail grid and viewer, the rest get
 * the shared media card. Duration and video size are not carried: the player measures them.
 */
@Immutable
data class UiMediaAttachment(
    val base64: String,
    val mediaType: String,
    /**
     * Round-tripped from a snapshot pointer whose inline thumbnail exceeded
     * the 16 KB budget. Non-null + empty base64 means the attachment exists
     * but the bytes were not rehydrated; renderers show a stored-pointer
     * placeholder labelled with [storedByteSize].
     */
    val storedByteSize: Long? = null,
    /** Explicit failure retained from the message attachment. */
    val unavailableReason: ImageUnavailableReason? = null,
    /** Remote source supported by the host image loader or media player. */
    val url: String? = null,
) {
    /** What this attachment is, from [mediaType] and then the extension of [url]. */
    val kind: MediaKind get() = MediaKind.of(mediaType, url)

    /** The file name in [url]'s path, fit to label a card; never the query, so never a token. */
    val displayName: String? get() = url?.substringBefore('?')?.substringBefore('#')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    /** The decoded size in bytes when known: the inline payload's, or the stored pointer's. */
    val byteSize: Long? get() = storedByteSize ?: base64.takeIf { it.isNotEmpty() }?.let { MediaLimits.decodedBytes(it.length) }

    /** The player source for this attachment, or null when its bytes were not kept and it has no URL. */
    fun playbackSource(): MediaSource? = when {
        url != null -> MediaSource.Remote(url)
        base64.isNotEmpty() -> MediaSource.Inline(base64, mediaType)
        else -> null
    }
}
