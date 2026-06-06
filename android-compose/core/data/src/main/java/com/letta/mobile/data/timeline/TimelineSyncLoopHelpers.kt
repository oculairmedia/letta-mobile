package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.model.MessageCreateRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val REQUEST_PREVIEW_MAX_CHARS = 2_048
private const val DATA_URL_PREVIEW_CHARS = 32

internal fun previewRequest(req: MessageCreateRequest, previewJson: Json): String {
    val root = previewJson.encodeToJsonElement(MessageCreateRequest.serializer(), req)
    val sanitizedRoot = root.jsonObject.let { rootObject ->
        val messages = rootObject["messages"]?.jsonArray?.map { redactMessage(it) }
        if (messages == null) {
            rootObject
        } else {
            JsonObject(rootObject + ("messages" to JsonArray(messages)))
        }
    }
    val preview = previewJson.encodeToString(JsonElement.serializer(), sanitizedRoot)
    return if (preview.length <= REQUEST_PREVIEW_MAX_CHARS) {
        preview
    } else {
        preview.take(REQUEST_PREVIEW_MAX_CHARS - 1) + "…"
    }
}

internal fun redactMessage(element: JsonElement): JsonElement {
    val message = element.jsonObject
    val content = message["content"]
    if (content !is JsonArray) return element
    return JsonObject(message + ("content" to redactContentParts(content)))
}

internal fun redactContentParts(content: JsonArray): JsonArray = JsonArray(
    content.map { part ->
        val partObject = part.jsonObject
        when (partObject["type"]?.jsonPrimitive?.contentOrNull) {
            "image" -> {
                val source = partObject["source"]?.jsonObject ?: return@map part
                if (source["type"]?.jsonPrimitive?.contentOrNull != "base64") return@map part
                val data = source["data"]?.jsonPrimitive?.contentOrNull ?: return@map part
                val mediaType = source["media_type"]?.jsonPrimitive?.contentOrNull ?: "?"
                JsonObject(
                    partObject + (
                        "source" to JsonObject(
                            source + ("data" to JsonPrimitive(previewBase64(mediaType, data)))
                        )
                    )
                )
            }
            "image_url" -> {
                val imageUrl = partObject["image_url"]?.jsonObject ?: return@map part
                val url = imageUrl["url"]?.jsonPrimitive?.contentOrNull ?: return@map part
                if (!url.startsWith("data:")) return@map part
                JsonObject(
                    partObject + (
                        "image_url" to JsonObject(
                            imageUrl + ("url" to JsonPrimitive(previewDataUrl(url)))
                        )
                    )
                )
            }
            else -> part
        }
    }
)

internal fun previewBase64(mediaType: String, base64: String): String {
    return "base64(${mediaType})=${base64.take(DATA_URL_PREVIEW_CHARS)}…[truncated, totalLen=${base64.length}]"
}

internal fun previewDataUrl(url: String): String {
    val prefix = "data:"
    val separator = ";base64,"
    val separatorIndex = url.indexOf(separator)
    if (!url.startsWith(prefix) || separatorIndex < 0) {
        return "[unsupported data url, totalLen=${url.length}]"
    }
    val mediaType = url.substring(prefix.length, separatorIndex)
    val base64 = url.substring(separatorIndex + separator.length)
    return "data:$mediaType;base64,${base64.take(DATA_URL_PREVIEW_CHARS)}…[truncated, totalLen=${url.length}]"
}

internal fun isRetryableReconcileError(t: Throwable): Boolean = when (t) {
    is ApiException -> t.code in 500..599
    else -> isTimelineNetworkFailure(t)
}

internal fun hydrateRawFetchLimit(visibleTarget: Int): Int =
    (visibleTarget * HYDRATE_RAW_FETCH_MULTIPLIER)
        .coerceIn(visibleTarget, HYDRATE_RAW_FETCH_MAX)

private const val HYDRATE_RAW_FETCH_MULTIPLIER = 5
private const val HYDRATE_RAW_FETCH_MAX = 500

