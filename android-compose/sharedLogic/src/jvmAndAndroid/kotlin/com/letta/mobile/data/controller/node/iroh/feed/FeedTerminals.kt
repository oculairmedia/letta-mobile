package com.letta.mobile.data.controller.node.iroh.feed

import com.letta.mobile.data.feed.wire.ConversationFeedWire
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A terminal delta (the end of a turn) is never stored as a pointer: a pointer is skipped on replay, and the host must never
 * lose a terminal. One over the inline cap is shortened instead, keeping what decides the turn's end (its type, stop reason,
 * status) and trimming the long free text.
 */
internal object FeedTerminals {
    private const val MAX_TEXT_CHARS = 4096
    private const val ELLIPSIS = "...[truncated]"
    private val KEPT_FIELDS = listOf("message_type", "stop_reason", "status", "run_id", "is_terminal")

    /** [delta] with its long text fields shortened; only its identifying fields when that is still over the inline cap. */
    fun compact(delta: JsonObject): JsonObject {
        val shortened = JsonObject(delta.mapValues { (_, value) -> shorten(value) })
        val fits = shortened.toString().toByteArray(Charsets.UTF_8).size <= ConversationFeedWire.MAX_INLINE_PAYLOAD_BYTES
        return if (fits) shortened else minimal(delta)
    }

    private fun shorten(value: JsonElement): JsonElement {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        return if (text != null && text.length > MAX_TEXT_CHARS) JsonPrimitive(text.take(MAX_TEXT_CHARS) + ELLIPSIS) else value
    }

    /** Only the fields that decide how the turn ended, each bounded; anything else is dropped. */
    private fun minimal(delta: JsonObject): JsonObject = buildJsonObject {
        KEPT_FIELDS.forEach { field -> delta[field]?.let { put(field, bounded(it)) } }
        put("truncated", true)
    }

    /** A kept field that is a long string is cut; one that is not a short string (an array, an object) is replaced by its size. */
    private fun bounded(value: JsonElement): JsonElement {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when {
            text != null -> JsonPrimitive(text.take(MAX_FIELD_CHARS))
            value is JsonPrimitive -> value
            else -> JsonPrimitive("[${value.toString().length} chars omitted]")
        }
    }
}

private const val MAX_FIELD_CHARS = 256
