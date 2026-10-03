package com.letta.mobile.data.plugin.view

import com.letta.mobile.data.schema.SchemaProblem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Why the bridge refused a view message: the JSON-RPC 2.0 codes, then the bridge's own (-32000 and below). */
enum class ViewErrorCode(val code: Int) {
    PARSE_ERROR(-32700),
    INVALID_REQUEST(-32600),
    METHOD_NOT_FOUND(-32601),
    INVALID_PARAMS(-32602),

    /** A call before `view.ready`. */
    NOT_READY(-32000),

    /** Over [LcpView.MAX_MESSAGES_PER_SECOND]. */
    RATE_LIMITED(-32001),

    /** Over [LcpView.MAX_MESSAGE_BYTES]. */
    TOO_LARGE(-32002),

    /** Something the manifest does not let this page do: an action not visible to views, an undeclared display mode or permission. */
    FORBIDDEN(-32003),

    /** The person or the host's policy said no (a link, a first-use permission). */
    DENIED(-32004),

    /** The plugin ran the action and it failed. */
    ACTION_FAILED(-32005),

    /** The host cannot be reached (offline, plugin disabled). */
    UNAVAILABLE(-32006),

    /** The view is being torn down or is gone. */
    CLOSED(-32007),
}

/** A JSON-RPC error the bridge answers with; [data] carries schema problems for [ViewErrorCode.INVALID_PARAMS]. */
data class ViewRpcError(val code: ViewErrorCode, val message: String, val data: JsonElement? = null) {
    fun toJson(): JsonObject = buildJsonObject {
        put("code", code.code)
        put("message", message)
        data?.let { put("data", it) }
    }

    companion object {
        /** An [ViewErrorCode.INVALID_PARAMS] error listing every [problems] at its pointer. */
        fun invalidParams(message: String, problems: List<SchemaProblem>): ViewRpcError = ViewRpcError(
            ViewErrorCode.INVALID_PARAMS,
            message,
            buildJsonObject { put("problems", JsonArray(problems.map(::problemJson))) },
        )

        private fun problemJson(problem: SchemaProblem): JsonObject = buildJsonObject {
            put("path", problem.path)
            put("code", problem.code)
            put("message", problem.message)
        }
    }
}

/** One view message after [ViewMessageValidator]: a call to answer, a reply to a host request, or a refusal. */
sealed interface ViewInbound {
    /** The id to answer with, or null for a notification (or a message too broken to have one). */
    val id: JsonPrimitive?

    /** A known method with params that hold to its schema; [id] is null exactly when [method] is a notification. */
    data class Call(override val id: JsonPrimitive?, val method: ViewMethod, val params: JsonObject) : ViewInbound

    /** The page's answer to a host request ([HostMethod.TEARDOWN]). */
    data class Reply(override val id: JsonPrimitive, val result: JsonElement?, val error: JsonObject?) : ViewInbound

    /** Refused before it reached the bridge's state; answered with [error] when [id] is known. */
    data class Refused(override val id: JsonPrimitive?, val error: ViewRpcError, val method: String? = null) : ViewInbound
}

/** JSON-RPC 2.0 messages the host sends. */
object ViewRpc {
    private const val VERSION = "2.0"

    fun result(id: JsonPrimitive, result: JsonElement): JsonObject = buildJsonObject {
        put("jsonrpc", VERSION)
        put("id", id)
        put("result", result)
    }

    /** An error answer; a message whose id could not be read is answered with `id: null`, as JSON-RPC asks. */
    fun error(id: JsonPrimitive?, error: ViewRpcError): JsonObject = buildJsonObject {
        put("jsonrpc", VERSION)
        put("id", id ?: JsonNull)
        put("error", error.toJson())
    }

    fun notification(method: String, params: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", VERSION)
        put("method", method)
        put("params", params)
    }

    fun request(id: JsonPrimitive, method: String, params: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", VERSION)
        put("id", id)
        put("method", method)
        put("params", params)
    }
}
