package com.letta.mobile.data.model

import com.letta.mobile.data.attachment.ImageUnavailableReason
import com.letta.mobile.data.media.MediaKind
import kotlinx.serialization.json.JsonObject

/**
 * The one parser for a media part found anywhere in a message or tool return: images in Letta's
 * flat and wrapped shapes and the legacy `image_url`, then audio, video and linked files through the
 * same decoders. Null for an object that is not a media part.
 */
internal fun parseMediaPart(obj: JsonObject): MessageContentPart.Image? = when (val type = obj.fieldContent("type")) {
    // Letta's native shape: { type:"image", source:{ type:"base64", media_type, data } | { type:"url", url } }
    "image" -> parseLettaImagePart(obj)
    // Legacy / OpenAI-style shape kept for backward compatibility with
    // anything previously persisted or echoed by older server builds.
    "image_url" -> (obj["image_url"] as? JsonObject)?.fieldContent("url")?.let { parseImageDataUrl(it) }
    // Audio and video take the image part's flat and wrapped shapes (MCP AudioContent is the flat one).
    "audio" -> parseTypedMediaPart(obj, MediaKind.Audio)
    "video" -> parseTypedMediaPart(obj, MediaKind.Video)
    else -> parseForeignMediaPart(type, obj)
}

/**
 * Dispatches to the per-shape image parsers.
 *
 * Two shapes are accepted:
 *   1. **Flat** `{ "type":"image", "data":"…", "mimeType":"…" }` — live wire
 *      and persisted rows occasionally emit this without a `source` wrapper;
 *      accepting the flat form preserves images forwarded between clients.
 *   2. **Wrapped** `{ "type":"image", "source": { "type":"base64"|"url"|"letta", … } }`
 *      — the canonical letta wire shape.
 *
 * Per-shape decoders live as siblings so the dispatcher stays a flat
 * "try flat, then wrapped" cascade and CodeScene's Complex-Method
 * biomarker (≤2 branches in the dispatch site) does not regress when a
 * fourth shape lands.
 */
private fun parseLettaImagePart(obj: JsonObject): MessageContentPart.Image =
    ImageUnavailableReason.fromWire(obj.fieldContent("unavailable_reason"))?.let { reason ->
        MessageContentPart.Image("", "image/unknown", unavailableReason = reason)
    } ?: parseFlatLettaImagePart(obj) ?: parseWrappedLettaImagePart(obj)
        ?: MessageContentPart.Image(
            base64 = obj.fieldContent("data").orEmpty(),
            mediaType = obj.fieldContent("mimeType") ?: obj.fieldContent("media_type") ?: "image/unknown",
            unavailableReason = when {
                !obj.fieldContent("data").isNullOrBlank() -> null
                obj["source"] != null || obj["image_ref"] != null -> ImageUnavailableReason.UnsupportedSource
                else -> ImageUnavailableReason.Missing
            },
        )

/**
 * Flat image shape: `{ "type":"image", "data":"…", "mimeType":"…" }`.
 * `media_type` is also accepted for letta wire compatibility; `mimeType`
 * wins when both are set (live wire uses `mimeType`; some legacy rows
 * persisted `media_type`). Returns null on missing/blank fields so the
 * wrapped-shape decoder still has a chance.
 */
private fun parseFlatLettaImagePart(obj: JsonObject): MessageContentPart.Image? {
    val data = obj.fieldContent("data")
    val mediaType = obj.fieldContent("mimeType") ?: obj.fieldContent("media_type")
    if (data.isNullOrBlank() || mediaType.isNullOrBlank()) return null
    return MessageContentPart.Image(base64 = data, mediaType = mediaType)
}

/**
 * Wrapped image shape: `{ "type":"image", "source": { "type":<variant>, … } }`.
 * Dispatches to the per-variant decoder based on `source.type`. Returns
 * null when `source` is absent, malformed, or names an unknown variant.
 */
private fun parseWrappedLettaImagePart(obj: JsonObject, fallback: MediaKind = MediaKind.Image): MessageContentPart.Image? {
    val source = obj["source"] as? JsonObject ?: return null
    return when (source.fieldContent("type")) {
        "base64" -> parseBase64ImageSource(source, fallback)
        "url" -> parseUrlImageSource(source, fallback)
        // "letta" — server-managed file reference. Empirically the server
        // inlines the raw base64 under `data` alongside a `file_id` that
        // would let us fetch via /v1/files if needed. Since the payload is
        // already present, decode it directly and render inline (no extra
        // network roundtrip). See bead letta-mobile-mge5.24.
        "letta" -> parseBase64ImageSource(source, fallback)
        else -> null
    }
}

/** Decodes a `source.type == "base64" | "letta"` image source. */
private fun parseBase64ImageSource(source: JsonObject, fallback: MediaKind): MessageContentPart.Image? {
    val mediaType = source.fieldContent("media_type")
    val data = source.fieldContent("data")
    return MessageContentPart.Image(
        base64 = data.orEmpty(), mediaType = mediaType?.takeIf { it.isNotBlank() } ?: fallback.unknownMimeType,
        unavailableReason = ImageUnavailableReason.Missing.takeIf { data.isNullOrBlank() },
    )
}

/** Decodes inline data URLs and HTTP sources supported by the host image loader. */
private fun parseUrlImageSource(source: JsonObject, fallback: MediaKind): MessageContentPart.Image? {
    val url = source.fieldContent("url") ?: return null
    return parseImageDataUrl(url, fallback)
}

private fun parseImageDataUrl(url: String, fallback: MediaKind = MediaKind.Image): MessageContentPart.Image? {
    if (url.startsWith("https://") || url.startsWith("http://")) {
        return MessageContentPart.Image("", fallback.unknownMimeType, url = url)
    }
    val prefix = "data:"
    val separator = ";base64,"
    val separatorIndex = url.indexOf(separator)
    if (!url.startsWith(prefix) || separatorIndex < 0) return null
    val mediaType = url.substring(prefix.length, separatorIndex)
    val base64 = url.substring(separatorIndex + separator.length)
    if (mediaType.isBlank() || base64.isBlank()) return null
    return MessageContentPart.Image(base64 = base64, mediaType = mediaType)
}

/**
 * An `audio` or `video` part in the image part's shapes: flat `{ data, mimeType }` (MCP AudioContent),
 * wrapped `{ source: { type: base64|url|letta } }`, or a bare `url`. Unlike an image, a part with no
 * payload yields nothing rather than a placeholder: tool results use `type: audio` for other things.
 */
private fun parseTypedMediaPart(obj: JsonObject, fallback: MediaKind): MessageContentPart.Image? {
    val part = parseFlatLettaImagePart(obj)
        ?: parseWrappedLettaImagePart(obj, fallback)
        ?: obj.fieldContent("url")?.let { parseImageDataUrl(it, fallback) }
        ?: return null
    if (part.base64.isEmpty() && part.url == null) return null
    return part.takeUnless { MediaKind.isUninformative(it.mediaType) } ?: part.copy(mediaType = fallback.unknownMimeType)
}

/**
 * Media in shapes other producers use: OpenAI `input_audio`, MCP `resource_link` (an http(s) link
 * with its own MIME type and name) and MCP `resource` with a base64 `blob`. Only audio and video
 * blobs are taken, so a text or image resource renders as it did before; links of any kind but
 * images are taken and drawn as a playable or downloadable card.
 */
private fun parseForeignMediaPart(type: String?, obj: JsonObject): MessageContentPart.Image? = when (type) {
    "input_audio" -> (obj["input_audio"] as? JsonObject)?.let { audio ->
        val data = audio.fieldContent("data")?.takeIf { it.isNotBlank() }
        data?.let { MessageContentPart.Image(base64 = it, mediaType = "audio/${audio.fieldContent("format") ?: "unknown"}") }
    }
    "resource_link" -> parseResourceLink(obj)
    "resource" -> (obj["resource"] as? JsonObject)?.let(::parseEmbeddedResourceBlob)
    else -> null
}

private fun parseResourceLink(obj: JsonObject): MessageContentPart.Image? {
    val uri = obj.fieldContent("uri")?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
    val mediaType = obj.fieldContent("mimeType")?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
    if (MediaKind.of(mediaType, obj.fieldContent("name") ?: uri) == MediaKind.Image) return null
    return MessageContentPart.Image("", mediaType, url = uri)
}

private fun parseEmbeddedResourceBlob(resource: JsonObject): MessageContentPart.Image? {
    val blob = resource.fieldContent("blob")?.takeIf { it.isNotBlank() } ?: return null
    val mediaType = resource.fieldContent("mimeType")?.takeIf { it.isNotBlank() } ?: return null
    val kind = MediaKind.of(mediaType, resource.fieldContent("uri"))
    return if (kind == MediaKind.Audio || kind == MediaKind.Video) MessageContentPart.Image(base64 = blob, mediaType = mediaType) else null
}
