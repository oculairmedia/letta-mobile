package com.letta.mobile.data.controller.extras.relay

import kotlin.jvm.JvmInline
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The app-tool relay: an Iroh host forwards a call to one of the tools that act on the app on screen
 * (they cannot run on the host) to the app viewing the caller's conversation, and returns the app's
 * answer as its own. Both sides advertise [CAPABILITY] in the auth exchange; a host sends
 * [REQUEST_TYPE] only to a connection that advertised it, and the app answers on its control stream
 * with [RESPONSE_TYPE]. Each side relays only the tools on its own allow-list.
 */
object AppToolRelay {
    /** The capability both peers advertise in the auth exchange. */
    const val CAPABILITY: String = "app_tool_relay_v1"

    /** Host to app: run this tool call. */
    const val REQUEST_TYPE: String = "app_tool_call_request"

    /** App to host: the answer to one [REQUEST_TYPE], by its call id. */
    const val RESPONSE_TYPE: String = "app_tool_call_response"
}

/**
 * Correlates one relayed call with its answer; the host mints it, the app echoes it.
 *
 * @property value the id as it travels in the frames' `call_id`.
 */
@JvmInline
value class AppToolCallId(val value: String) {
    /** The id itself, so it reads plainly in logs and messages. */
    override fun toString(): String = value
}

/**
 * Who a relayed call comes from: the runtime scope the host's App Server stamped on the tool call,
 * never model input. Each is null when the host's call carried none.
 *
 * @property agentId the calling agent.
 * @property conversationId the conversation the agent runs in.
 * @property toolCallId the model's tool call id in the run.
 */
data class AppToolCallScope(
    val agentId: String? = null,
    val conversationId: String? = null,
    val toolCallId: String? = null,
)

/**
 * One relayed tool call.
 *
 * @property callId the id the answer must carry.
 * @property toolName the tool to run.
 * @property input the model's input, unchanged.
 * @property scope who the call comes from.
 */
data class AppToolCallRequest(
    val callId: AppToolCallId,
    val toolName: String,
    val input: JsonObject,
    val scope: AppToolCallScope,
)

/**
 * What a relayed tool returned.
 *
 * @property text the tool's result, or its error when [isError].
 * @property isError whether the tool failed or refused.
 */
data class AppToolCallResult(val text: String, val isError: Boolean = false)

/**
 * The app's answer to one [AppToolCallRequest].
 *
 * @property callId the request's id.
 * @property result what the tool returned.
 */
data class AppToolCallResponse(val callId: AppToolCallId, val result: AppToolCallResult)

/**
 * The relay frames on the wire. Decoding ignores fields it does not know, so either side may add
 * fields later; a frame without a call id is not a relay frame and decodes to null.
 */
object AppToolRelayFrames {
    private const val TYPE = "type"
    private const val CALL_ID = "call_id"
    private const val TOOL_NAME = "tool_name"
    private const val INPUT = "input"
    private const val AGENT_ID = "agent_id"
    private const val CONVERSATION_ID = "conversation_id"
    private const val TOOL_CALL_ID = "tool_call_id"
    private const val SUCCESS = "success"
    private const val CONTENT = "content"
    private const val ERROR = "error"
    private const val NO_RESULT = "The app answered without a result."

    /** The frame's `type`, or null when it has none. */
    fun typeOf(frame: JsonObject): String? = frame.string(TYPE)

    /** [request] as a [AppToolRelay.REQUEST_TYPE] frame. */
    fun encode(request: AppToolCallRequest): String = buildJsonObject {
        put(TYPE, AppToolRelay.REQUEST_TYPE)
        put(CALL_ID, request.callId.value)
        put(TOOL_NAME, request.toolName)
        put(INPUT, request.input)
        request.scope.agentId?.let { put(AGENT_ID, it) }
        request.scope.conversationId?.let { put(CONVERSATION_ID, it) }
        request.scope.toolCallId?.let { put(TOOL_CALL_ID, it) }
    }.toString()

    /** [response] as a [AppToolRelay.RESPONSE_TYPE] frame. */
    fun encode(response: AppToolCallResponse): String = buildJsonObject {
        put(TYPE, AppToolRelay.RESPONSE_TYPE)
        put(CALL_ID, response.callId.value)
        put(SUCCESS, !response.result.isError)
        put(if (response.result.isError) ERROR else CONTENT, response.result.text)
    }.toString()

    /**
     * A [AppToolRelay.REQUEST_TYPE] frame, or null when [frame] is another type or has no call id.
     * A missing tool name or a non-object input still decodes (to an empty name or input), so the app
     * can refuse it by its call id instead of leaving the host waiting.
     */
    fun decodeRequest(frame: JsonObject): AppToolCallRequest? {
        val callId = callIdOf(frame, AppToolRelay.REQUEST_TYPE) ?: return null
        val scope = AppToolCallScope(
            agentId = frame.string(AGENT_ID),
            conversationId = frame.string(CONVERSATION_ID),
            toolCallId = frame.string(TOOL_CALL_ID),
        )
        val input = frame[INPUT] as? JsonObject ?: JsonObject(emptyMap())
        return AppToolCallRequest(callId, frame.string(TOOL_NAME).orEmpty(), input, scope)
    }

    /** A [AppToolRelay.RESPONSE_TYPE] frame, or null when [frame] is another type or has no call id. */
    fun decodeResponse(frame: JsonObject): AppToolCallResponse? {
        val callId = callIdOf(frame, AppToolRelay.RESPONSE_TYPE) ?: return null
        val content = frame.string(CONTENT)
        val succeeded = (frame[SUCCESS] as? JsonPrimitive)?.booleanOrNull == true
        val result = if (succeeded && content != null) {
            AppToolCallResult(content)
        } else {
            AppToolCallResult(frame.string(ERROR) ?: NO_RESULT, isError = true)
        }
        return AppToolCallResponse(callId, result)
    }

    private fun callIdOf(frame: JsonObject, type: String): AppToolCallId? {
        if (typeOf(frame) != type) return null
        return frame.string(CALL_ID)?.takeIf { it.isNotBlank() }?.let(::AppToolCallId)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
