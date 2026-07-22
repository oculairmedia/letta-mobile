package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Identifies whether a text frame contains a provider delta or a complete snapshot. */
internal enum class StreamTextFrameSource {
    AppServerDelta,
    CumulativeSnapshot,
}

/**
 * Collapses streamed assistant and reasoning text into one cumulative body per
 * message identity. Replayed envelopes remain idempotent by frame ID.
 */
internal class CumulativeStreamText {
    private val byKey = LinkedHashMap<String, String>()
    private val seenFrameIds = LinkedHashSet<String>()

    fun applyToRawFrame(
        rawFrame: String,
        source: StreamTextFrameSource,
    ): String = runCatching {
        val frame = parseTextFrame(rawFrame) ?: return@runCatching rawFrame
        val existing = byKey[frame.textKey].orEmpty()
        val cumulative = accumulatedText(frame, existing, source)
        byKey[frame.textKey] = cumulative
        frame.withText(cumulative).toString()
    }.getOrDefault(rawFrame)

    private fun accumulatedText(
        frame: StreamTextFrame,
        existing: String,
        source: StreamTextFrameSource,
    ): String {
        if (frame.isReplay) return existing.ifEmpty { frame.chunk }
        return when (source) {
            StreamTextFrameSource.AppServerDelta -> existing + frame.chunk
            StreamTextFrameSource.CumulativeSnapshot -> frame.chunk
        }
    }

    private fun parseTextFrame(rawFrame: String): StreamTextFrame? {
        val envelope = AppServerProtocol.json.parseToJsonElement(rawFrame).jsonObject
        if (envelope["type"]?.jsonPrimitive?.contentOrNull != "stream_delta") return null
        return envelope["delta"]?.jsonObject?.let { delta -> parseTextDelta(envelope, delta) }
    }

    private fun parseTextDelta(envelope: JsonObject, delta: JsonObject): StreamTextFrame? {
        val messageType = delta["message_type"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it in STREAM_TEXT_MESSAGE_TYPES }
            ?: return null
        val field = if (messageType == "assistant_message") "content" else "reasoning"
        val chunk = textFrom(delta[field]) ?: textFrom(delta["content"]) ?: textFrom(delta["text"]) ?: return null
        val frameId = envelope["idempotency_key"]?.jsonPrimitive?.contentOrNull
        return StreamTextFrame(
            envelope = envelope,
            delta = delta,
            messageType = messageType,
            field = field,
            chunk = chunk,
            textKey = textKey(delta, messageType),
            isReplay = frameId != null && !seenFrameIds.add(frameId),
        )
    }

    private fun textKey(delta: JsonObject, messageType: String): String =
        delta["otid"]?.jsonPrimitive?.contentOrNull
            ?: delta["id"]?.jsonPrimitive?.contentOrNull
            ?: delta["message_id"]?.jsonPrimitive?.contentOrNull
            ?: messageType

    private fun textFrom(element: JsonElement?): String? {
        if (element == null) return null
        (element as? JsonPrimitive)?.contentOrNull?.let { return it }
        val array = element as? JsonArray ?: return null
        return array.joinToString("") { part -> textPartFrom(part) }.takeIf { it.isNotEmpty() }
    }

    private fun textPartFrom(part: JsonElement): String = runCatching {
        val obj = part.jsonObject
        if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") {
            obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        } else {
            ""
        }
    }.getOrDefault("")

    private data class StreamTextFrame(
        val envelope: JsonObject,
        val delta: JsonObject,
        val messageType: String,
        val field: String,
        val chunk: String,
        val textKey: String,
        val isReplay: Boolean,
    ) {
        fun withText(cumulative: String): JsonObject {
            val cumulativeDelta = buildJsonObject {
                delta.forEach { (key, value) -> if (key != field) put(key, value) }
                put(field, cumulative)
                if (messageType != "assistant_message" && !delta.containsKey("reasoning")) {
                    put("reasoning", cumulative)
                }
            }
            return buildJsonObject {
                envelope.forEach { (key, value) -> if (key != "delta") put(key, value) }
                put("delta", cumulativeDelta)
            }
        }
    }

    private companion object {
        val STREAM_TEXT_MESSAGE_TYPES = setOf(
            "assistant_message",
            "reasoning_message",
            "hidden_reasoning_message",
        )
    }
}
