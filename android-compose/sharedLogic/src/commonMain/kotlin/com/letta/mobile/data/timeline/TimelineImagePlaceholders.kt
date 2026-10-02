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
    val held = mapNotNullTo(HashSet()) { image -> image.base64.takeIf { it.isNotEmpty() } }
    val pool = incoming.filterTo(ArrayList()) { it.base64.isNotEmpty() && it.base64 !in held }
    if (pool.isEmpty()) return this
    var filled = false
    val result = map { image ->
        if (!image.isSizeOnlyPlaceholder) return@map image
        val sameType = pool.indices.filter { pool[it].mediaType.equals(image.mediaType, ignoreCase = true) }
        val pick = sameType.firstOrNull { pool[it].estimatedDecodedBytes() == image.storedByteSize }
            ?: sameType.firstOrNull()
            ?: return@map image
        filled = true
        pool.removeAt(pick)
    }
    return if (filled) result else this
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
    if (filled === this) return (this + incoming).distinct()
    val used = filled.mapNotNullTo(HashSet()) { image -> image.base64.takeIf { it.isNotEmpty() } }
    return (filled + incoming.filterNot { it.base64.isNotEmpty() && it.base64 in used }).distinct()
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
