package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.MessageContentPart

/**
 * An image a snapshot kept only as a size pointer: the attachment exists, but its bytes were
 * not restored on this device. Renderers show it as a placeholder.
 */
val MessageContentPart.Image.isSizeOnlyPlaceholder: Boolean
    get() = base64.isEmpty() && storedByteSize != null

/** Decoded size of [MessageContentPart.Image.base64], estimated the same way the snapshot codec records it. */
internal fun MessageContentPart.Image.estimatedDecodedBytes(): Long {
    val padding = base64.takeLast(2).count { it == '=' }
    return (base64.length * 3L) / 4L - padding
}

/**
 * Fills each size-only placeholder with a byte-bearing image from [incoming] of the same media
 * type, preferring the one whose decoded size matches the placeholder's. Real images and
 * placeholders without a candidate are kept; order is preserved. Returns this same list when
 * nothing was filled.
 */
internal fun List<MessageContentPart.Image>.fillSizeOnlyPlaceholders(
    incoming: List<MessageContentPart.Image>,
): List<MessageContentPart.Image> {
    if (none { it.isSizeOnlyPlaceholder }) return this
    val held = filter { it.base64.isNotEmpty() }.mapTo(HashSet()) { it.toolCallId to it.base64 }
    val pool = incoming.filterTo(ArrayList()) { it.base64.isNotEmpty() && (it.toolCallId to it.base64) !in held }
    if (pool.isEmpty()) return this
    var filled = false
    val result = map { image ->
        val pick = if (image.isSizeOnlyPlaceholder) pool.bestFillFor(image) else null
        if (pick == null) return@map image
        filled = true
        pool.removeAt(pick)
    }
    return if (filled) result else this
}

/** Index of the pooled image that fills [placeholder]: same call and type, matching size first; null when none. */
private fun List<MessageContentPart.Image>.bestFillFor(placeholder: MessageContentPart.Image): Int? {
    val candidates = indices.filter { this[it].canFill(placeholder) }
    return candidates.firstOrNull { this[it].estimatedDecodedBytes() == placeholder.storedByteSize }
        ?: candidates.firstOrNull()
}

/** Same owning call (when the placeholder names one) and same media type, ignoring case. */
private fun MessageContentPart.Image.canFill(placeholder: MessageContentPart.Image): Boolean {
    if (placeholder.toolCallId != null && toolCallId != placeholder.toolCallId) return false
    return mediaType.equals(placeholder.mediaType, ignoreCase = true)
}

/**
 * [this] followed by the images of [incoming] it lacks, with any size-only placeholder replaced
 * by the incoming image that carries its bytes, so a row never shows a placeholder next to the
 * very image it stands for.
 */
internal fun List<MessageContentPart.Image>.mergeIncomingImages(
    incoming: List<MessageContentPart.Image>,
): List<MessageContentPart.Image> {
    val filled = fillSizeOnlyPlaceholders(incoming)
    val seen = HashSet<Pair<String?, String>>()
    return (filled + incoming).filter { image ->
        image.base64.isEmpty() || seen.add(image.toolCallId to image.base64)
    }.distinct()
}

/**
 * Gives the confirmed row that [incoming] names the bytes its size-only placeholders lack.
 * Returns this timeline unchanged when there is no such row or nothing to fill.
 */
internal fun Timeline.withImagePlaceholdersFilledBy(incoming: TimelineEvent.Confirmed): Timeline =
    withImagePlaceholdersFilled(incoming.serverId, incoming.attachments, incoming.messageType)

internal fun Timeline.withImagePlaceholdersFilled(
    serverId: String,
    images: List<MessageContentPart.Image>,
    messageType: TimelineMessageType? = null,
): Timeline {
    if (images.none { it.base64.isNotEmpty() }) return this
    val existing = findByServerId(serverId, messageType) ?: return this
    val filled = existing.attachments.fillSizeOnlyPlaceholders(images)
    if (filled === existing.attachments) return this
    return replaceByServerId(existing.copy(attachments = filled.toTimelinePersistentList()))
}
