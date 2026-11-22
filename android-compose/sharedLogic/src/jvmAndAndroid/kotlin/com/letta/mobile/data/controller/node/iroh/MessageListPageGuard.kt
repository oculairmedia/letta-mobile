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
 * admin_rpc (letta-mobile-c4igq.9, letta-mobile-vzn89).
 *
 * A ~60MB conversation transcript serialized into one admin_rpc response is
 * rejected by the frame layer (admin_rpc.stream.response_too_large,
 * maxFrameBytes=1MiB). Long histories are the product, so the serve path must
 * PAGINATE, not prune: every message.list page is bounded by bytes as well as by
 * the row count the App Server applied.
 *
 * A page is anchored where the request is (the rows nearest its cursor, or the newest / oldest
 * rows without one) and grows away from that anchor one indivisible unit at a time: a tool call
 * and its returns in the page always travel together. It stops before the unit that would cross
 * the byte budget, so the client continues from the last row it received and loses nothing.
 * Only the first unit may exceed the budget, and only for a peer that negotiated frame_part, up
 * to its logical response cap. Incapable peers use the page budget as their hard cap. Inline images
 * that exceed that cap become explicit too-large markers.
 *
 * Wire additions (only when the guard trims): the response is wrapped as
 * { "messages": [...], "has_more": true, "next_before": "<farthest-kept-id>" } (or `next_after`
 * for an ascending page) so the client can request the next window. When the page already fits,
 * the raw array/response is returned unchanged (small-conversation hydration is byte-for-byte identical).
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

    /** Room for the trimmed page's `{"messages":…,"has_more":true,"next_before":"<id>"}` wrapper. */
    private const val PAGE_WRAPPER_BYTES = 256

    private const val NEXT_BEFORE = "next_before"
    private const val NEXT_AFTER = "next_after"

    /**
     * Bound one App Server `message.list` page for the direction it was requested in. [ascending]
     * is the App Server's own reading of `order` (only `"asc"`; anything else, including no order,
     * is its newest-first default). Both directions keep the rows nearest the request's cursor and
     * name the farthest kept row as the continuation: `next_after` ascending, `next_before` otherwise.
     */
    internal fun boundPage(
        projected: JsonElement,
        ascending: Boolean,
        maxResponseBytes: Int,
        maxPageBytes: Int = MAX_PAGE_BYTES,
    ): JsonElement = pack(projected, maxPageBytes, maxResponseBytes, anchorAtFront = true, if (ascending) NEXT_AFTER else NEXT_BEFORE)

    /**
     * Bound [projected] (a message.list projection — a JsonArray or {messages:[]})
     * so it fits under [maxPageBytes]. Returns the same shape when it already
     * fits; otherwise a trimmed { messages, has_more, next_before } object.
     *
     * [newestLast]: true when the array is oldest->newest, so the NEWEST rows are at the end and
     * the page keeps that end; false when it is newest->oldest and the page keeps the front.
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
    ): JsonElement = pack(projected, maxPageBytes, maxResponseBytes, anchorAtFront = !newestLast, NEXT_BEFORE)

    private fun pack(
        projected: JsonElement,
        maxPageBytes: Int,
        maxResponseBytes: Int,
        anchorAtFront: Boolean,
        cursorKey: String,
    ): JsonElement {
        val rowBudget = maxResponseBytes - PAGE_METADATA_BYTES
        val pageBudget = minOf(maxPageBytes, rowBudget)
        val original = extractMessages(projected) ?: return projected.alsoLogUnboundableShape()
        fun preservePageShape(rows: List<JsonElement>): JsonElement = when {
            rows.size == original.size && rows.indices.all { rows[it] === original[it] } -> projected
            projected is JsonObject -> JsonObject(projected + ("messages" to JsonArray(rows)))
            else -> JsonArray(rows)
        }
        val inAnchorOrder = if (anchorAtFront) original else original.reversed()
        val lengths = IntArray(inAnchorOrder.size) { index -> byteLen(inAnchorOrder[index]) }
        val rows = inAnchorOrder.mapIndexed { index, row ->
            if (lengths[index] <= rowBudget) row else stripInlineImages(row).also { lengths[index] = byteLen(it) }
        }.toMutableList()
        if (totalByteLen(lengths, rows.size) <= pageBudget) {
            return preservePageShape(if (anchorAtFront) rows else rows.reversed())
        }
        val keptCount = keptPrefix(rows, lengths, pageBudget - PAGE_WRAPPER_BYTES, rowBudget)
        val keptInAnchorOrder = rows.subList(0, keptCount).toList()
        val kept = if (anchorAtFront) keptInAnchorOrder else keptInAnchorOrder.reversed()
        if (keptCount == rows.size) return preservePageShape(kept)
        // The farthest kept row from the anchor is where the next page continues. For an oldest->newest
        // array anchored at its end that is the FRONT of `kept`; otherwise its BACK (P0.2: the front of a
        // newest-first page is its NEWEST row and would overlap or skip pages).
        val farthestId = (keptInAnchorOrder.last() as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
        return buildJsonObject {
            put("messages", JsonArray(kept))
            put("has_more", JsonPrimitive(true))
            if (farthestId != null) put(cursorKey, JsonPrimitive(farthestId))
        }
    }

    /**
     * How many rows from the anchor (index 0) the page keeps. A prefix is a valid page only when it is
     * closed: no tool call in it has a return beyond it and no return in it has its call beyond it.
     * The longest closed prefix within [packBudget] wins. When even the first closed prefix is over
     * [packBudget] it is kept alone (frame_part carries it) if it fits [rowBudget] once its inline
     * images are stripped; failing that, the anchor row is served by itself.
     */
    private fun keptPrefix(rows: MutableList<JsonElement>, lengths: IntArray, packBudget: Int, rowBudget: Int): Int {
        val reach = unitReach(rows)
        var bytes = 1 // "["; each row adds its length plus one comma or "]"
        var farthest = 0
        var kept = 0
        for (index in rows.indices) {
            bytes += lengths[index] + 1
            farthest = maxOf(farthest, reach[index])
            if (farthest != index) continue
            if (bytes > packBudget) return if (kept > 0) kept else firstUnit(rows, lengths, index + 1, rowBudget)
            kept = index + 1
        }
        return kept
    }

    /** The first closed prefix of [count] rows, kept whole when it fits [rowBudget] (images stripped if needed), else its anchor row. */
    private fun firstUnit(rows: MutableList<JsonElement>, lengths: IntArray, count: Int, rowBudget: Int): Int {
        fun bytes() = (0 until count).sumOf { lengths[it].toLong() + 1 } + 1
        if (bytes() > rowBudget) {
            for (index in 0 until count) {
                rows[index] = stripInlineImages(rows[index])
                lengths[index] = byteLen(rows[index])
            }
        }
        return if (bytes() <= rowBudget) count else 1
    }

    /** For each row, the farthest index its tool call / return partners in this page sit at (itself when none). */
    private fun unitReach(rows: List<JsonElement>): IntArray {
        val reach = IntArray(rows.size) { it }
        val lastById = HashMap<String, Int>()
        rows.forEachIndexed { index, row -> toolCallIdOf(row)?.let { id -> lastById[id] = index } }
        rows.forEachIndexed { index, row ->
            toolCallIdOf(row)?.let { id -> reach[index] = maxOf(index, lastById.getValue(id)) }
        }
        return reach
    }

    /** The tool call id that joins a call row (`tool_call.tool_call_id`) to its return rows (`tool_call_id`). */
    private fun toolCallIdOf(row: JsonElement): String? {
        val obj = row as? JsonObject ?: return null
        val call = obj["tool_call"] as? JsonObject
        return (call?.get("tool_call_id") ?: obj["tool_call_id"])?.let { (it as? JsonPrimitive)?.contentOrNull }
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

    /** UTF-8 size of the first [count] elements as a JSON array ("[]" + elements + commas). */
    private fun totalByteLen(lengths: IntArray, count: Int): Int {
        if (count == 0) return 2 // "[]"
        var bytes = 2 + (count - 1) // brackets + inter-element commas
        for (index in 0 until count) bytes += lengths[index]
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
