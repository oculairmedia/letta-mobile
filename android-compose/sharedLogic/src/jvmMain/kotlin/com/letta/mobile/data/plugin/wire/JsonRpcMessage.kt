package com.letta.mobile.data.plugin.wire

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A JSON-RPC 2.0 error object. */
data class JsonRpcError(val code: Int, val message: String, val data: JsonElement? = null)

/**
 * One JSON-RPC 2.0 message as LCP accepts it: params are always an object (absent reads as `{}`),
 * ids are a string or a number, batches do not exist.
 */
sealed interface JsonRpcMessage {
    data class Request(val id: JsonPrimitive, val method: String, val params: JsonObject) : JsonRpcMessage

    data class Notification(val method: String, val params: JsonObject) : JsonRpcMessage

    data class Success(val id: JsonPrimitive, val result: JsonElement) : JsonRpcMessage

    /** [id] is null when the message it answers had none that could be read. */
    data class Failure(val id: JsonPrimitive?, val error: JsonRpcError) : JsonRpcMessage
}

/** A call that ended in a JSON-RPC error: the remote's answer, or a local refusal with the same codes. */
class LcpCallException(val error: JsonRpcError) : Exception("${error.code}: ${error.message}") {
    constructor(code: Int, message: String, data: JsonElement? = null) : this(JsonRpcError(code, message, data))

    val code: Int get() = error.code
}

/** The outcome of reading one line or frame. */
sealed interface JsonRpcDecoding {
    data class Decoded(val message: JsonRpcMessage) : JsonRpcDecoding

    /** The message could not be accepted; [answer] is what the reader sends back (none for a broken notification). */
    data class Malformed(val error: JsonRpcError, val id: JsonPrimitive? = null, val answerable: Boolean = true) : JsonRpcDecoding {
        val answer: JsonRpcMessage.Failure? get() = if (answerable) JsonRpcMessage.Failure(id, error) else null
    }
}
