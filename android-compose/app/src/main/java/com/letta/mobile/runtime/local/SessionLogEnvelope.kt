package com.letta.mobile.runtime.local

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Session-log **v3 envelope** handling for the embedded runtime's on-disk
 * `messages.jsonl` (letta-mobile-6ppdr).
 *
 * letta-code 0.29.x does NOT write bare message rows. Every message line is an
 * ENVELOPE emitted by the bundle's `localTranscriptSessionEntries`:
 *
 * ```json
 * {"type":"message","id":"…","parentId":"…","timestamp":"…","message":{"id":…,"role":…,"content":[…]}}
 * ```
 *
 * plus a leading `{"type":"session","version":3,…}` header line. Legacy stores
 * (pre-0.29) hold FLAT rows where `role`/`content` sit at the top level; the
 * live store at the time of writing held 89,991 envelope rows vs 27 flat ones,
 * so both shapes must keep working.
 *
 * The read/projection path already unwraps correctly
 * (`LocalBackendMessageReader.normalizeMessage` — the reference implementation
 * this object mirrors). The MUTATING passes ([LocalImageContextStripper],
 * [LocalConversationHealer]) and [LettaCodeLocalBackendStore.readToolResults]
 * used to read the top level only, which made them silent no-ops on every real
 * 0.29.x transcript.
 *
 * Rewrite safety: [withBody] replaces ONLY the nested `message` object and
 * leaves every other top-level field — including fields this codebase has never
 * heard of — in place and in its original declaration order. That is what makes
 * a stripper rewrite non-lossy: letta.js keeps loading the row, and any future
 * envelope field the bundle adds survives a round trip.
 */
object SessionLogEnvelope {

    /** The `type` discriminator of a v3 message envelope. */
    const val MESSAGE_ENVELOPE_TYPE: String = "message"

    /** The `type` discriminator of the v3 session header line. */
    const val SESSION_HEADER_TYPE: String = "session"

    /**
     * True when [row] is a session-log v3 message envelope, i.e. `type ==
     * "message"` AND `message` is an object. Mirrors
     * `LocalBackendMessageReader.normalizeMessage`'s unwrap condition exactly —
     * anything else (flat legacy row, session header, junk) is false.
     */
    fun isEnvelope(row: JsonObject): Boolean =
        row["type"].asStringOrNull() == MESSAGE_ENVELOPE_TYPE && row["message"] is JsonObject

    /** True for the `{"type":"session",…}` header line, which carries no message at all. */
    fun isSessionHeader(row: JsonObject): Boolean =
        row["type"].asStringOrNull() == SESSION_HEADER_TYPE

    /**
     * The message body of [row]: the nested `message` object for a v3 envelope,
     * or the row itself for a legacy flat row. `role` / `content` / `toolCallId`
     * must always be read from THIS object, never from the raw row.
     */
    fun body(row: JsonObject): JsonObject =
        if (isEnvelope(row)) row["message"] as JsonObject else row

    /**
     * Rebuilds [row] carrying [newBody] as its message body.
     *
     * For a v3 envelope: a copy of the envelope with ONLY `message` swapped —
     * every other key keeps its value AND its position (LinkedHashMap `put` on
     * an existing key preserves insertion order), so `type`, `id`, `parentId`,
     * `timestamp` and any unknown/future field round-trip untouched.
     *
     * For a flat legacy row: the body IS the row, so the new body is returned
     * directly (callers are expected to have preserved the flat row's own
     * unknown fields when deriving [newBody]).
     */
    fun withBody(row: JsonObject, newBody: JsonObject): JsonObject =
        if (isEnvelope(row)) {
            JsonObject(LinkedHashMap<String, JsonElement>(row).apply { put("message", newBody) })
        } else {
            newBody
        }

    /**
     * Wraps a freshly synthesized flat message [body] in an envelope shaped like
     * [anchor] when the transcript is v3, so appended rows stay loadable by
     * letta.js's own session-log reader (and by this app's reader) exactly like
     * the rows around them. Returns [body] unchanged for a flat transcript.
     *
     * `parentId`/`timestamp` are copied from the anchor envelope (the row the
     * synthetic one is inserted after) when present, keeping the emitted row
     * DETERMINISTIC — re-running a heal regenerates byte-identical bytes, which
     * the healer's idempotency check relies on.
     */
    fun wrapLike(anchor: JsonObject?, body: JsonObject, entryId: String): JsonObject {
        if (anchor == null || !isEnvelope(anchor)) return body
        return JsonObject(
            buildMap {
                put("type", JsonPrimitive(MESSAGE_ENVELOPE_TYPE))
                put("id", JsonPrimitive(entryId))
                put("parentId", anchor["id"] ?: JsonPrimitive(null as String?))
                anchor["timestamp"]?.let { put("timestamp", it) }
                put("message", body)
            },
        )
    }

    private fun JsonElement?.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
