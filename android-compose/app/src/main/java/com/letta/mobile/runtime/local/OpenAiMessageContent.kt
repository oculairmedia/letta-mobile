package com.letta.mobile.runtime.local

import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * letta-mobile-by4vm.121.16: the parts of one OpenAI chat message [OpenAiChatTranslation] reads,
 * kept whole: its text parts in order and every image part.
 */

/** A string field, or null when absent or not a string. */
internal fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** OpenAI message `content`: a string, or typed parts whose text parts are kept in order. */
internal fun JsonObject.contentText(): String = when (val content = this["content"]) {
    is JsonPrimitive -> content.contentOrNull.orEmpty()
    is JsonArray -> content.mapNotNull { part ->
        val item = part as? JsonObject
        val type = item?.stringField("type")
        item?.takeIf { type == null || type in TEXT_PART_TYPES }?.stringField("text")
    }.joinToString("\n")
    else -> ""
}

/** Every `image_url` part of the message, decoded; a part that is not a base64 image is rejected, not skipped. */
internal fun JsonObject.images(): List<OnDeviceImage> =
    (this["content"] as? JsonArray).orEmpty().mapNotNull { part ->
        val item = part as? JsonObject ?: return@mapNotNull null
        if (item.stringField("type") != "image_url") return@mapNotNull null
        val url = when (val imageUrl = item["image_url"]) {
            is JsonPrimitive -> imageUrl.contentOrNull
            is JsonObject -> imageUrl.stringField("url")
            else -> null
        } ?: throw OnDeviceRequestRejected(OpenAiChatTranslation.INVALID_REQUEST, "An image_url part has no url.")
        decodeOpenAiImageUrl(url) ?: throw OnDeviceRequestRejected(
            OpenAiChatTranslation.INVALID_REQUEST,
            "An image_url part is not a base64 image (only data: URLs and raw base64 reach the device).",
        )
    }

/** A `data:<type>;base64,<payload>` URL or bare base64; null when it is neither. */
internal fun decodeOpenAiImageUrl(url: String): OnDeviceImage? {
    val trimmed = url.trim()
    val isDataUrl = trimmed.startsWith(DATA_PREFIX, ignoreCase = true)
    val markerIndex = trimmed.indexOf(BASE64_MARKER, ignoreCase = true)
    if (isDataUrl && markerIndex < 0) return null
    val mediaType = if (isDataUrl) trimmed.substring(DATA_PREFIX.length, markerIndex).takeIf { it.isNotBlank() } else null
    val payload = if (isDataUrl) trimmed.substring(markerIndex + BASE64_MARKER.length) else trimmed
    return runCatching { OnDeviceImage(Base64.getDecoder().decode(payload), mediaType) }.getOrNull()
}

private val TEXT_PART_TYPES = setOf("text", "input_text")
private const val DATA_PREFIX = "data:"
private const val BASE64_MARKER = ";base64,"
