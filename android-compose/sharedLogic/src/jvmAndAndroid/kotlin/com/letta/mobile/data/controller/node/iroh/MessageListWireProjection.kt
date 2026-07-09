package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.SyntheticSkillEnvelopeDetector
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * letta-mobile-fe51r (P2b pointer diet): server-side wire projection for
 * iroh admin_rpc `message.list` responses.
 *
 * Kitchen testing showed a 50-message page weighing ~1.4MB (avg ~28KB per
 * message) — mostly tool-return bodies — which blows the 1MiB iroh frame
 * budget. Instead of shipping full payloads on every hydrate:
 *
 * - Tool-return bodies larger than [TOOL_RETURN_PROJECTION_THRESHOLD_BYTES]
 *   are replaced with a ~[TOOL_RETURN_PREVIEW_BYTES] preview and the message
 *   is stamped with `tool_return_truncated`, `tool_return_byte_len`, and a
 *   `tool_return_pointer` that names the `tool_return.get` admin_rpc method
 *   clients use to fetch the full body on demand (e.g. when the user expands
 *   a tool card).
 * - Inline attachment payloads (base64 image data / data: URLs) ship
 *   unmodified. Clients have no refetch path for omitted attachment data, so
 *   stripping it would silently lose images on every hydrate; oversized
 *   pages ride the capability-gated `frame_part` chunking instead.
 *
 * `tool_return.get` and `message.get` responses are NOT projected.
 */
object MessageListWireProjection {
    /** Bodies at or below this many UTF-8 bytes ship inline unmodified. */
    const val TOOL_RETURN_PROJECTION_THRESHOLD_BYTES: Int = 4096

    /** Maximum UTF-8 bytes of the original body kept in a projected preview. */
    const val TOOL_RETURN_PREVIEW_BYTES: Int = 2048

    const val POINTER_METHOD: String = "tool_return.get"

    private val TOOL_RETURN_MESSAGE_TYPES = setOf("tool_return_message", "tool_return")

    /**
     * Projects a full `message.list` response. Accepts either a bare JSON
     * array of messages or an object wrapping one under `messages`; any other
     * shape is returned untouched.
     */
    fun projectMessageList(response: JsonElement, conversationId: String): JsonElement = when {
        response is JsonArray -> JsonArray(response.mapNotNull { projectElement(it, conversationId) })
        response is JsonObject && response["messages"] is JsonArray -> JsonObject(
            response.toMutableMap().apply {
                this["messages"] = JsonArray((response["messages"] as JsonArray).mapNotNull { projectElement(it, conversationId) })
            },
        )
        else -> response
    }

    private fun projectElement(element: JsonElement, conversationId: String): JsonElement? =
        if (element is JsonObject) projectMessage(element, conversationId) else element

    /** Projects a single message object for a list response. Returns null for suppressed messages. */
    fun projectMessage(message: JsonObject, conversationId: String): JsonObject? {
        // letta-mobile-dz5a8 (P1): suppress synthetic skill-instruction envelopes
        // from projection. These are role:user messages whose content starts with
        // or contains <skill ...> / <skill-name> XML-ish blocks (often with trailing
        // "ARGUMENTS:" line). The persisted backend row stays intact for model
        // context; only the wire projection filters them out so they don't render
        // as giant blue user bubbles on the phone.
        if (isSyntheticSkillInstructionEnvelope(message)) {
            return null
        }

        val messageType = (message["message_type"] as? JsonPrimitive)?.contentOrNull
        if (messageType !in TOOL_RETURN_MESSAGE_TYPES) return message
        return projectToolReturnMessage(message, conversationId)
    }

    private fun isSyntheticSkillInstructionEnvelope(message: JsonObject): Boolean {
        val role = (message["role"] as? JsonPrimitive)?.contentOrNull
        val content = (message["content"] as? JsonPrimitive)?.contentOrNull ?: return false
        return SyntheticSkillEnvelopeDetector.isSyntheticSkillEnvelope(role, content)
    }

    private fun projectToolReturnMessage(message: JsonObject, conversationId: String): JsonObject {
        val out = message.toMutableMap()
        var truncated = false
        var originalBytes = 0L

        (message["tool_return"])?.let { raw ->
            val body = bodyString(raw)
            val size = utf8ByteLength(body)
            if (size > TOOL_RETURN_PROJECTION_THRESHOLD_BYTES) {
                truncated = true
                originalBytes += size
                out["tool_return"] = JsonPrimitive(preview(body, size))
            }
        }

        (message["tool_returns"] as? JsonArray)?.let { returns ->
            var changed = false
            val projected = returns.map { entry ->
                if (entry !is JsonObject) return@map entry
                val funcResponse = entry["func_response"] ?: return@map entry
                val body = bodyString(funcResponse)
                val size = utf8ByteLength(body)
                if (size <= TOOL_RETURN_PROJECTION_THRESHOLD_BYTES) return@map entry
                truncated = true
                changed = true
                originalBytes += size
                JsonObject(entry.toMutableMap().apply { this["func_response"] = JsonPrimitive(preview(body, size)) })
            }
            if (changed) out["tool_returns"] = JsonArray(projected)
        }

        for (streamKey in listOf("stdout", "stderr")) {
            val lines = (message[streamKey] as? JsonArray) ?: continue
            val joined = lines.joinToString("\n") { bodyString(it) }
            val size = utf8ByteLength(joined)
            if (size > TOOL_RETURN_PROJECTION_THRESHOLD_BYTES) {
                truncated = true
                originalBytes += size
                out[streamKey] = JsonArray(listOf(JsonPrimitive(preview(joined, size))))
            }
        }

        if (!truncated) return message
        out["tool_return_truncated"] = JsonPrimitive(true)
        out["tool_return_byte_len"] = JsonPrimitive(originalBytes)
        out["tool_return_pointer"] = JsonObject(
            mapOf(
                "method" to JsonPrimitive(POINTER_METHOD),
                "conversation_id" to JsonPrimitive(conversationId),
                "message_id" to JsonPrimitive((message["id"] as? JsonPrimitive)?.contentOrNull ?: ""),
            ),
        )
        return JsonObject(out)
    }

    private fun bodyString(element: JsonElement): String =
        if (element is JsonPrimitive && element.isString) element.content else element.toString()

    private fun preview(body: String, originalBytes: Int): String =
        utf8SafePrefix(body, TOOL_RETURN_PREVIEW_BYTES) +
            "\n… [truncated: $originalBytes bytes total — expand to load the full output]"

    internal fun utf8ByteLength(s: String): Int {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return bytes
    }

    /** Longest prefix of [s] whose UTF-8 encoding fits in [maxBytes], never splitting a code point. */
    internal fun utf8SafePrefix(s: String, maxBytes: Int): String {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val step: Int
            val chars: Int
            when {
                c.code < 0x80 -> { step = 1; chars = 1 }
                c.code < 0x800 -> { step = 2; chars = 1 }
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> { step = 4; chars = 2 }
                else -> { step = 3; chars = 1 }
            }
            if (bytes + step > maxBytes) break
            bytes += step
            i += chars
        }
        return s.substring(0, i)
    }
}
