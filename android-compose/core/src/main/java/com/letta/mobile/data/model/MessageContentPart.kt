package com.letta.mobile.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One piece of a multimodal message payload.
 *
 * The Letta server accepts `content` on `MessageCreate` as either a plain string
 * (single text) or a JSON array of typed parts. The wire format for the array
 * mirrors the OpenAI-style content parts:
 *
 * ```json
 * [
 *   { "type": "text", "text": "hello" },
 *   { "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,..." } }
 * ]
 * ```
 *
 * Text-only messages continue to go through the legacy string path to minimize
 * server-side compatibility surface.
 */
sealed class MessageContentPart {
    data class Text(val text: String) : MessageContentPart()

    /**
     * An inline image. Encoded as a data: URL on the wire so the backend never
     * has to reach out to a user's private URLs.
     *
     * @param base64 the base64-encoded image bytes (no data: prefix)
     * @param mediaType MIME type, e.g. "image/jpeg" or "image/png"
     */
    data class Image(val base64: String, val mediaType: String) : MessageContentPart() {
        fun toDataUrl(): String = "data:$mediaType;base64,$base64"
    }
}

/** Serialize a list of content parts as a JsonArray suitable for MessageCreate.content. */
fun List<MessageContentPart>.toJsonArray(): JsonArray {
    val objs = map { part ->
        when (part) {
            is MessageContentPart.Text -> JsonObject(
                mapOf(
                    "type" to JsonPrimitive("text"),
                    "text" to JsonPrimitive(part.text),
                )
            )
            is MessageContentPart.Image -> JsonObject(
                mapOf(
                    "type" to JsonPrimitive("image_url"),
                    "image_url" to JsonObject(
                        mapOf("url" to JsonPrimitive(part.toDataUrl()))
                    ),
                )
            )
        }
    }
    return JsonArray(objs)
}

/** Drop-in helper: build a `[text, ...images]` part list. */
fun buildContentParts(
    text: String,
    images: List<MessageContentPart.Image>,
): List<MessageContentPart> {
    val list = mutableListOf<MessageContentPart>()
    if (text.isNotBlank()) list.add(MessageContentPart.Text(text))
    list.addAll(images)
    return list
}
