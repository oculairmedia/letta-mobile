package com.letta.mobile.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One piece of a multimodal message payload.
 *
 * The Letta server accepts `content` on `MessageCreate` as either a plain string
 * (single text) or a JSON array of typed parts. The wire format for the array
 * is **Letta's own discriminated union** (NOT OpenAI's `image_url` shape):
 *
 * ```json
 * [
 *   { "type": "text", "text": "hello" },
 *   {
 *     "type": "image",
 *     "source": {
 *       "type": "base64",
 *       "media_type": "image/jpeg",
 *       "data": "<raw base64, no data: prefix>"
 *     }
 *   }
 * ]
 * ```
 *
 * The server schema (`LettaMessageContentUnion` → `ImageContent` → `Base64Image`)
 * is verified against the running Letta API at `/openapi.json`. Sending an
 * OpenAI-style `{type:"image_url", image_url:{url:"data:..."}}` part fails with
 * a `string_type` validation error because the union has no `image_url`
 * discriminator (see bead `letta-mobile-cm9a`).
 *
 * Text-only messages continue to go through the legacy string path to minimize
 * server-side compatibility surface.
 */
@Immutable
sealed class MessageContentPart {
    @Immutable
    data class Text(val text: String) : MessageContentPart()

    /**
     * An inline image. On the wire this is encoded as a Letta `ImageContent`
     * with a `Base64Image` source. The `data` field is the raw base64 payload
     * with NO `data:` URL prefix — the server's pydantic schema rejects the
     * prefixed form.
     *
     * @param base64 the base64-encoded image bytes (no `data:` prefix)
     * @param mediaType MIME type, e.g. "image/jpeg" or "image/png"
     */
    @Immutable
    data class Image(val base64: String, val mediaType: String) : MessageContentPart() {
        /**
         * Build an `data:<mediaType>;base64,<base64>` URL. NOT used on the
         * outbound wire (see class docs); kept for in-app rendering callers
         * that feed image bytes into Coil/AsyncImage.
         */
        fun toDataUrl(): String = "data:$mediaType;base64,$base64"
    }
}

/**
 * Serialize a list of content parts as a `JsonArray` suitable for
 * `MessageCreate.content`. Emits Letta's `LettaMessageContentUnion` shape —
 * see class docs on [MessageContentPart] for the exact schema.
 */
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
                    "type" to JsonPrimitive("image"),
                    "source" to JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("base64"),
                            "media_type" to JsonPrimitive(part.mediaType),
                            "data" to JsonPrimitive(part.base64),
                        )
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
