package com.letta.mobile.data.plugin.view

import com.letta.mobile.data.schema.JsonSchemaCheck
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Holds every message a page sends to `lcp-view/1` (plan section 7.2) before the bridge looks at
 * it: at most [LcpView.MAX_MESSAGE_BYTES], a JSON-RPC 2.0 object with no other members, an id that
 * is a short string or an integer, a method of [ViewMethod] with an id exactly when it is a request,
 * and params that hold to the method's closed schema. Stateless; the bridge adds the state gates.
 */
object ViewMessageValidator {
    private val MEMBERS = setOf("jsonrpc", "id", "method", "params", "result", "error")
    private val ANSWER_MEMBERS = setOf("result", "error")
    private val VERSION = JsonPrimitive("2.0")
    private val NO_PARAMS = JsonObject(emptyMap())
    private val checks: Map<ViewMethod, JsonSchemaCheck> = ViewMethod.entries.associateWith { JsonSchemaCheck(it.params) }

    fun validate(raw: String): ViewInbound {
        if (isTooLarge(raw)) return refused(null, ViewErrorCode.TOO_LARGE, "a message is at most ${LcpView.MAX_MESSAGE_BYTES} bytes")
        val message = parse(raw) ?: return refused(null, ViewErrorCode.PARSE_ERROR, "the message is not JSON")
        if (message !is JsonObject) return invalid(null, "a message is a JSON-RPC 2.0 object")
        val id = message["id"]?.let(::readId)
        envelopeProblem(message, id)?.let { return invalid(id, it) }
        return if ("method" in message) call(message, id) else reply(message, id)
    }

    /** [element] as a request id: a string of 1 to [LcpView.MAX_ID_LENGTH] characters or an integer; null otherwise. */
    fun readId(element: JsonElement): JsonPrimitive? {
        val primitive = element as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.takeIf { it.content.length in 1..LcpView.MAX_ID_LENGTH } else primitive.takeIf { it.content.toLongOrNull() != null }
    }

    private fun isTooLarge(raw: String): Boolean = raw.length > LcpView.MAX_MESSAGE_BYTES || raw.encodeToByteArray().size > LcpView.MAX_MESSAGE_BYTES

    private fun parse(raw: String): JsonElement? = try {
        Json.parseToJsonElement(raw)
    } catch (_: SerializationException) {
        null
    }

    private fun envelopeProblem(message: JsonObject, id: JsonPrimitive?): String? = when {
        message["jsonrpc"] != VERSION -> "\"jsonrpc\" must be \"2.0\""
        (message.keys - MEMBERS).isNotEmpty() -> "unknown members: ${(message.keys - MEMBERS).joinToString()}"
        hasUnreadableId(message, id) -> "an id is a string of 1 to ${LcpView.MAX_ID_LENGTH} characters or an integer"
        else -> null
    }

    private fun hasUnreadableId(message: JsonObject, id: JsonPrimitive?): Boolean = "id" in message && id == null

    private fun call(message: JsonObject, id: JsonPrimitive?): ViewInbound {
        val name = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return invalid(id, "\"method\" is a string")
        if (message.keys.any { it in ANSWER_MEMBERS }) return invalid(id, "a call carries no result or error")
        val method = ViewMethod.of(name) ?: return refused(id, ViewErrorCode.METHOD_NOT_FOUND, "'$name' is not an lcp-view/1 method", name)
        idMismatch(method, id)?.let { return refused(id, ViewErrorCode.INVALID_REQUEST, it, name) }
        return withParams(method, id, message["params"] ?: NO_PARAMS)
    }

    private fun withParams(method: ViewMethod, id: JsonPrimitive?, params: JsonElement): ViewInbound {
        if (params !is JsonObject) return refused(id, ViewErrorCode.INVALID_PARAMS, "params are an object", method.wire)
        val problems = checks.getValue(method).check(params, "/params")
        if (problems.isNotEmpty()) return ViewInbound.Refused(id, ViewRpcError.invalidParams("${method.wire} params do not hold", problems), method.wire)
        return ViewInbound.Call(id, method, params)
    }

    private fun idMismatch(method: ViewMethod, id: JsonPrimitive?): String? = when {
        method.notification && id != null -> "${method.wire} is a notification; it carries no id"
        !method.notification && id == null -> "${method.wire} is a request; it needs an id"
        else -> null
    }

    private fun reply(message: JsonObject, id: JsonPrimitive?): ViewInbound {
        if (id == null) return invalid(null, "a message is a call (\"method\") or an answer with an id")
        val answers = message.keys.intersect(ANSWER_MEMBERS)
        if (answers.size != 1) return invalid(id, "an answer carries exactly one of result or error")
        val error = message["error"]
        if (error != null && error !is JsonObject) return invalid(id, "an error is an object")
        return ViewInbound.Reply(id, message["result"], error as? JsonObject)
    }

    private fun invalid(id: JsonPrimitive?, message: String): ViewInbound = refused(id, ViewErrorCode.INVALID_REQUEST, message)

    private fun refused(id: JsonPrimitive?, code: ViewErrorCode, message: String, method: String? = null): ViewInbound =
        ViewInbound.Refused(id, ViewRpcError(code, message), method)
}
