package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.util.attr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.letta.mobile.util.Telemetry

/**
 * Server-side page-size guard for message.list over Iroh
 * admin_rpc.
 *
 * A ~60MB conversation transcript serialized into one admin_rpc response is
 * rejected by the frame layer (admin_rpc.stream.response_too_large,
 * maxFrameBytes=1MiB). Long histories are the product, so the serve path must
 * PAGINATE, not prune: this guard bounds every message.list page to stay under
 * the frame cap. The byte budget is soft for an indivisible row or call/return window when
 * frame_part was negotiated, up to the logical response cap. Incapable peers use the page budget
 * as their hard cap. Inline images that exceed that cap become explicit too-large markers.
 * If the projected page
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

    /** Room for array brackets, cursor metadata and the RPC envelope within the hard budget. */
    private const val PAGE_METADATA_BYTES = 1024

    /**
     * Bound [projected] (a message.list projection — a JsonArray or {messages:[]})
     * so it fits under [maxPageBytes]. Returns the same shape when it already
     * fits; otherwise a trimmed { messages, has_more, next_before } object.
     *
     * [newestLast]: true when the array is oldest->newest (default Letta order),
     * so the NEWEST rows are at the end and we trim from the FRONT.
     * [maxResponseBytes] is the peer's hard response budget, including page metadata; the caller
     * supplies [MAX_PAGE_BYTES] for peers without frame_part. Inline images that make a row or
     * atomic window exceed that budget are replaced with unavailable markers, retaining IDs/text.
     * Calls outside the projected page cannot be restored by this guard.
     */
    fun bound(
        projected: JsonElement,
        maxPageBytes: Int = MAX_PAGE_BYTES,
        newestLast: Boolean = true,
        maxResponseBytes: Int = IrohFrameCodec.DEFAULT_MAX_REASSEMBLED_BYTES - MAX_PAGE_BYTES,
    ): JsonElement {
        val rowBudget = maxResponseBytes - PAGE_METADATA_BYTES
        val pageBudget = minOf(maxPageBytes, rowBudget)
        val original = extractMessages(projected) ?: return projected.alsoLogUnboundableShape()
        val lengths = IntArray(original.size) { index -> byteLen(original[index]) }
        val messages = original.mapIndexed { index, row ->
            if (lengths[index] <= rowBudget) row else stripInlineImages(row).also { lengths[index] = byteLen(it) }
        }.toMutableList()
        val totalBytes = totalByteLen(lengths)
        if (projected !is JsonObject && totalBytes <= pageBudget) {
            return if (messages.indices.all { messages[it] === original[it] }) projected else JsonArray(messages)
        }
        // Keep the newest rows that fit under the budget.
        val keptIndices = ArrayDeque<Int>()
        var bytes = 2 // "[]"
        val orderedIndices = if (newestLast) {
            messages.indices.reversed()
        } else {
            messages.indices
        }
        for (index in orderedIndices) {
            val add = lengths[index] + 1
            if (keptIndices.isNotEmpty() && bytes + add > pageBudget) break
            if (newestLast) keptIndices.addFirst(index) else keptIndices.addLast(index)
            bytes += add
        }
        keepInvokingCalls(messages, lengths, keptIndices, rowBudget)
        val kept = keptIndices.map { messages[it] }
        val trimmed = kept.size < messages.size
        val keptArray = JsonArray(kept.toList())
        if (!trimmed) return keptArray
        // P0.2: `kept` is always oldest->newest (addFirst on the reversed newest-first
        // stream, addLast on the natural newest-first stream). The oldest kept row —
        // the `next_before` cursor for the older window — is at the FRONT when
        // newestLast, and at the BACK otherwise. Both branches previously returned
        // firstOrNull(), so newestLast=false emitted the NEWEST id -> overlapping/skipped pages.
        val oldestKept = if (newestLast) kept.firstOrNull() else kept.lastOrNull()
        val oldestKeptId = (oldestKept as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
        return buildJsonObject {
            put("messages", keptArray)
            put("has_more", JsonPrimitive(true))
            if (oldestKeptId != null) put("next_before", JsonPrimitive(oldestKeptId))
        }
    }

    /**
     * A message.list response that is neither a bare array nor
     * { messages: [...] } is passed through UNCHANGED by this guard, by
     * MessageListWireProjection.projectMessageList (`else -> response`), and then by
     * the client, which hands it to a ListSerializer and dies with
     * "Expected JsonArray, but had JsonObject" — hydration fails and the
     * conversation renders empty, with nothing anywhere naming the actual shape.
     *
     * Log the SHAPE so the next occurrence is diagnosable: element type, top-level
     * keys, and size. Never the values — a message.list body is conversation
     * content, and keys alone identify the producing tier.
     */
    private fun JsonElement.alsoLogUnboundableShape(): JsonElement {
        val kind = when (this) {
            is JsonObject -> "object"
            is JsonArray -> "array"
            is JsonPrimitive -> if (isString) "string" else "primitive"
            else -> "unknown"
        }
        val keys = (this as? JsonObject)?.keys.orEmpty().sorted().take(MAX_LOGGED_KEYS)
        Telemetry.event(
            "IrohNode", "message_list.unboundable_shape",
            "kind" attr kind,
            "keyCount" attr ((this as? JsonObject)?.size ?: 0),
            "keys" attr keys.joinToString(","),
            "byteLen" attr byteLen(this),
            level = Telemetry.Level.WARN,
        )
        return this
    }

    /** Bounded so a pathological object cannot turn a diagnostic into a huge log line. */
    private const val MAX_LOGGED_KEYS = 12

    private fun extractMessages(el: JsonElement): List<JsonElement>? = when (el) {
        is JsonArray -> el.toList()
        is JsonObject if el["messages"] is JsonArray -> (el["messages"] as JsonArray).toList()
        else -> null
    }

    private fun byteLen(el: JsonElement): Int = utf8ByteLength(el.toString())

    /** UTF-8 size without allocating an intermediate ByteArray. */
    private fun utf8ByteLength(text: String): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val c = text[i].code
            when {
                c <= 0x7F -> {
                    bytes += 1
                    i += 1
                }
                c <= 0x7FF -> {
                    bytes += 2
                    i += 1
                }
                c in 0xD800..0xDBFF && i + 1 < text.length && text[i + 1].code in 0xDC00..0xDFFF -> {
                    bytes += 4
                    i += 2
                }
                else -> {
                    bytes += 3
                    i += 1
                }
            }
        }
        return bytes
    }

    /** UTF-8 size of the elements as a JSON array ("[]" + elements + commas). */
    private fun totalByteLen(lengths: IntArray): Int {
        if (lengths.isEmpty()) return 2 // "[]"
        var bytes = 2 + (lengths.size - 1) // brackets + inter-element commas
        for (len in lengths) bytes += len
        return bytes
    }

    /**
     * Bound an object response (e.g. agent.context) that is
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
        val out = buildJsonObject {
            for ((key, value) in response) {
                val prim = value as? JsonPrimitive
                val str = prim?.contentOrNull
                if (prim != null && prim.isString && str != null && str.encodeToByteArray().size > maxFieldBytes) {
                    val prefix = MessageListWireProjection.utf8SafePrefix(str, maxFieldBytes)
                    val marker = "\n… [truncated: " + str.encodeToByteArray().size + " bytes]"
                    put(key, JsonPrimitive(prefix + marker))
                } else {
                    put(key, value)
                }
            }
        }
        return out
    }

    /**
     * Drop [field] from a JSON object response. Used by agent.context: the
     * /context endpoint inlines the FULL in-context `messages` array (e.g. 27,868
     * messages / ~96MB on a large conversation) that NO client reads
     * (ContextWindowOverview consumes only the counts + memory strings; the
     * transcript comes via message.list) yet which blows the 1MiB admin_rpc frame
     * (response_too_large). [boundObjectStringFields] only trims oversized STRING
     * fields, so the heavy array must be removed explicitly. Returns the input
     * unchanged if it is not a JSON object or lacks [field].
     */
    fun dropField(response: JsonElement, field: String): JsonElement {
        if (response !is JsonObject || field !in response) return response
        return buildJsonObject {
            for ((key, value) in response) if (key != field) put(key, value)
        }
    }

    /** Keep an invoking call in the same page as its return, preserving cursor continuity. */
    private fun keepInvokingCalls(
        messages: MutableList<JsonElement>, lengths: IntArray, kept: ArrayDeque<Int>, maxResponseBytes: Int,
    ) {
        if (kept.isEmpty()) return
        val callIndices = messages.indices.mapNotNull { index ->
            val call = (messages[index] as? JsonObject)?.get("tool_call") as? JsonObject
            call?.get("tool_call_id")?.let { it to index }
        }.toMap()
        val returnedIds = kept.mapNotNull { (messages[it] as? JsonObject)?.get("tool_call_id") }
        val dependencies = returnedIds.mapNotNull(callIndices::get)
        val first = minOf(kept.first(), dependencies.minOrNull() ?: kept.first())
        val last = maxOf(kept.last(), dependencies.maxOrNull() ?: kept.last())
        var bytes = (first..last).sumOf { lengths[it].toLong() + 1 }
        if (bytes > maxResponseBytes) {
            for (index in first..last) {
                messages[index] = stripInlineImages(messages[index])
                lengths[index] = messages[index].toString().encodeToByteArray().size
            }
            bytes = (first..last).sumOf { lengths[it].toLong() + 1 }
        }
        if (bytes > maxResponseBytes) return
        kept.clear()
        for (index in first..last) kept.addLast(index)
    }

    /** Replace inline images, including JSON-string tool returns, without dropping row/call identity or text. */
    private fun stripInlineImages(value: JsonElement): JsonElement {
        fun isJsonContainerString(value: JsonPrimitive, text: String): Boolean =
            value.isString && (text.startsWith("[") || text.startsWith("{"))
        return when (value) {
            is JsonArray -> JsonArray(value.map(::stripInlineImages))
            is JsonObject -> when (value["type"]?.let { (it as? JsonPrimitive)?.contentOrNull }) {
                "image", "image_url" -> buildJsonObject {
                    put("type", JsonPrimitive("image"))
                    put("unavailable_reason", JsonPrimitive("too_large"))
                }
                else -> JsonObject(value.mapValues { (_, child) -> stripInlineImages(child) })
            }
            is JsonPrimitive -> {
                val text = value.contentOrNull.orEmpty()
                val parsed = if (isJsonContainerString(value, text)) {
                    runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) }.getOrNull()
                } else null
                if (parsed == null) value else JsonPrimitive(stripInlineImages(parsed).toString())
            }
            else -> value
        }
    }
}
