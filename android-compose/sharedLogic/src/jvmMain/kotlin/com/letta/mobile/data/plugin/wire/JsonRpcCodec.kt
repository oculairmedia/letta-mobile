package com.letta.mobile.data.plugin.wire

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The JSON-RPC 2.0 envelope of LCP wire v1, shared by the NDJSON and WebSocket framings: one
 * message per text, at most [LcpWire.MAX_MESSAGE_BYTES] of UTF-8, batches refused, params always
 * an object. Encoding is compact, so an encoded message never holds a raw line break.
 */
object JsonRpcCodec {
    private val EMPTY = JsonObject(emptyMap())

    fun encode(message: JsonRpcMessage): String = LcpWire.json.encodeToString(JsonObject.serializer(), toJson(message))

    fun toJson(message: JsonRpcMessage): JsonObject = buildJsonObject {
        put("jsonrpc", LcpWire.JSONRPC)
        when (message) {
            is JsonRpcMessage.Request -> {
                put("id", message.id)
                put("method", message.method)
                put("params", message.params)
            }
            is JsonRpcMessage.Notification -> {
                put("method", message.method)
                put("params", message.params)
            }
            is JsonRpcMessage.Success -> {
                put("id", message.id)
                put("result", message.result)
            }
            is JsonRpcMessage.Failure -> {
                put("id", message.id ?: JsonNull)
                put("error", errorJson(message.error))
            }
        }
    }

    private fun errorJson(error: JsonRpcError): JsonObject = buildJsonObject {
        put("code", error.code)
        put("message", error.message)
        error.data?.let { put("data", it) }
    }

    /** [text] as a message, or why it is refused (with the answer to send, when one is due). */
    fun decode(text: String): JsonRpcDecoding {
        if (utf8Size(text) > LcpWire.MAX_MESSAGE_BYTES) return malformed(LcpErrorCode.MESSAGE_TOO_LARGE, "a message is at most ${LcpWire.MAX_MESSAGE_BYTES} bytes")
        val element = try {
            LcpWire.json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return malformed(LcpErrorCode.PARSE_ERROR, "not JSON")
        }
        return decode(element)
    }

    /** An already parsed [element] as a message. */
    fun decode(element: JsonElement): JsonRpcDecoding {
        if (element is JsonArray) return malformed(LcpErrorCode.INVALID_REQUEST, "batches are not supported")
        val obj = element as? JsonObject ?: return malformed(LcpErrorCode.INVALID_REQUEST, "a message is a JSON object")
        val id = idOf(obj)
        if (obj["jsonrpc"].stringOrNull() != LcpWire.JSONRPC) return malformed(LcpErrorCode.INVALID_REQUEST, "jsonrpc must be \"2.0\"", id)
        return when {
            "method" in obj -> decodeCall(obj, id)
            "result" in obj || "error" in obj -> decodeResponse(obj, id)
            else -> malformed(LcpErrorCode.INVALID_REQUEST, "neither a call nor a response", id)
        }
    }

    private fun decodeCall(obj: JsonObject, id: JsonPrimitive?): JsonRpcDecoding {
        val answerable = "id" in obj
        val method = obj["method"].stringOrNull()
            ?: return malformed(LcpErrorCode.INVALID_REQUEST, "method must be a string", id, answerable)
        if (answerable && id == null) return malformed(LcpErrorCode.INVALID_REQUEST, "id must be a string or a number")
        val params = paramsOf(obj) ?: return malformed(LcpErrorCode.INVALID_PARAMS, "params must be an object", id, answerable)
        val message = if (id == null) JsonRpcMessage.Notification(method, params) else JsonRpcMessage.Request(id, method, params)
        return JsonRpcDecoding.Decoded(message)
    }

    private fun decodeResponse(obj: JsonObject, id: JsonPrimitive?): JsonRpcDecoding {
        val result = obj["result"]
        val error = obj["error"]
        return when {
            result != null && error != null -> unanswerable("a response has a result or an error, not both")
            result != null && id != null -> JsonRpcDecoding.Decoded(JsonRpcMessage.Success(id, result))
            error != null -> errorOf(error)?.let { JsonRpcDecoding.Decoded(JsonRpcMessage.Failure(id, it)) } ?: unanswerable("error must hold a code and a message")
            else -> unanswerable("a result needs a string or number id")
        }
    }

    private fun errorOf(element: JsonElement): JsonRpcError? {
        val obj = element as? JsonObject ?: return null
        val code = (obj["code"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return null
        val message = obj["message"].stringOrNull() ?: return null
        return JsonRpcError(code, message, obj["data"])
    }

    private fun paramsOf(obj: JsonObject): JsonObject? = when (val params = obj["params"]) {
        null -> EMPTY
        is JsonObject -> params
        else -> null
    }

    /** A string or numeric id; null, booleans and structures are not ids. */
    private fun idOf(obj: JsonObject): JsonPrimitive? {
        val id = obj["id"] as? JsonPrimitive ?: return null
        return id.takeIf { it !is JsonNull && (it.isString || it.doubleOrNull != null) }
    }

    private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun malformed(code: Int, message: String, id: JsonPrimitive? = null, answerable: Boolean = true): JsonRpcDecoding.Malformed =
        JsonRpcDecoding.Malformed(JsonRpcError(code, message), id, answerable)

    private fun unanswerable(message: String): JsonRpcDecoding.Malformed = malformed(LcpErrorCode.INVALID_REQUEST, message, answerable = false)

    /** The UTF-8 length of [text] without encoding it. */
    fun utf8Size(text: String): Int {
        var size = 0
        var index = 0
        while (index < text.length) {
            val char = text[index]
            size += utf8Width(char)
            if (char.isHighSurrogate()) index++
            index++
        }
        return size
    }

    private fun utf8Width(char: Char): Int = when {
        char.code < 0x80 -> 1
        char.code < 0x800 -> 2
        char.isHighSurrogate() -> 4
        else -> 3
    }
}
