package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * letta-mobile-c4igq.9: server-side page-size guard for message.list over Iroh
 * admin_rpc.
 *
 * A ~60MB conversation transcript serialized into one admin_rpc response is
 * rejected by the frame layer (admin_rpc.stream.response_too_large,
 * maxFrameBytes=1MiB). Long histories are the product, so the serve path must
 * PAGINATE, not prune: this guard bounds every message.list page to stay under
 * the frame cap. It NEVER lets a response exceed the cap — if the projected page
 * is still too large it shrinks the window (dropping the OLDEST rows, keeping the
 * newest so the chat opens at the tail) and marks the response with a
 * continuation cursor so the client can load older windows.
 *
 * Wire additions (only when the guard trims): the response is wrapped as
 * { "messages": [...], "has_more": true, "next_before": "<oldest-kept-id>" } so
 * the existing `before`-cursor pager can request the next older window. When the
 * page already fits, the raw array/response is returned unchanged (small-
 * conversation hydration is byte-for-byte identical).
 */
object MessageListPageGuard {

    /** Default newest-window size when the caller sends no explicit limit. */
    const val DEFAULT_PAGE_LIMIT = 50

    /**
     * Safe per-page byte budget: well under IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES
     * (1 MiB) with headroom for the response envelope + frame framing.
     */
    const val MAX_PAGE_BYTES = 900 * 1024

    /**
     * Bound [projected] (a message.list projection — a JsonArray or {messages:[]})
     * so it fits under [maxPageBytes]. Returns the same shape when it already
     * fits; otherwise a trimmed { messages, has_more, next_before } object.
     *
     * [newestLast]: true when the array is oldest->newest (default Letta order),
     * so the NEWEST rows are at the end and we trim from the FRONT.
     */
    fun bound(
        projected: JsonElement,
        maxPageBytes: Int = MAX_PAGE_BYTES,
        newestLast: Boolean = true,
    ): JsonElement {
        val messages = extractMessages(projected) ?: return projected
        if (byteLen(JsonArray(messages)) <= maxPageBytes && !isWrapped(projected)) {
            return projected
        }
        // Keep the newest rows that fit under the budget.
        val kept = ArrayDeque<JsonElement>()
        var bytes = 2 // "[]"
        val ordered = if (newestLast) messages.asReversed() else messages
        for (msg in ordered) {
            val add = byteLen(msg) + 1
            if (kept.isNotEmpty() && bytes + add > maxPageBytes) break
            if (newestLast) kept.addFirst(msg) else kept.addLast(msg)
            bytes += add
        }
        val trimmed = kept.size < messages.size
        val keptArray = JsonArray(kept.toList())
        if (!trimmed) return keptArray
        val oldestKeptId = idOf(if (newestLast) kept.firstOrNull() else kept.firstOrNull())
        return buildJsonObject {
            put("messages", keptArray)
            put("has_more", kotlinx.serialization.json.JsonPrimitive(true))
            if (oldestKeptId != null) put("next_before", kotlinx.serialization.json.JsonPrimitive(oldestKeptId))
        }
    }

    private fun extractMessages(el: JsonElement): List<JsonElement>? = when {
        el is JsonArray -> el.toList()
        el is JsonObject && el["messages"] is JsonArray -> (el["messages"] as JsonArray).toList()
        else -> null
    }

    private fun isWrapped(el: JsonElement): Boolean = el is JsonObject && el["messages"] is JsonArray

    private fun idOf(el: JsonElement?): String? =
        (el as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull

    private fun byteLen(el: JsonElement): Int = el.toString().encodeToByteArray().size

    /**
     * letta-mobile-c4igq.9: bound an object response (e.g. agent.context) that is
     * otherwise small but for a few large string fields. Truncates any string
     * whose UTF-8 length exceeds [maxFieldBytes] to a safe prefix + a marker, so
     * the whole response stays comfortably under the frame cap. Non-string fields
     * (counts/stats) are untouched. Returns the input unchanged if it already
     * fits.
     */
    fun boundObjectStringFields(
        response: JsonElement,
        maxFieldBytes: Int = 128 * 1024,
        maxTotalBytes: Int = MAX_PAGE_BYTES,
    ): JsonElement {
        if (response !is JsonObject) return response
        if (byteLen(response) <= maxTotalBytes) return response
        val out = kotlinx.serialization.json.buildJsonObject {
            for ((key, value) in response) {
                val prim = value as? kotlinx.serialization.json.JsonPrimitive
                val str = prim?.contentOrNull
                if (prim != null && prim.isString && str != null && str.encodeToByteArray().size > maxFieldBytes) {
                    val prefix = MessageListWireProjection.utf8SafePrefix(str, maxFieldBytes)
                    val marker = "\n… [truncated: " + str.encodeToByteArray().size + " bytes]"
                    put(key, kotlinx.serialization.json.JsonPrimitive(prefix + marker))
                } else {
                    put(key, value)
                }
            }
        }
        return out
    }
}
