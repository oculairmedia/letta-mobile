package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.iroh.canonicalToolCalls
import com.letta.mobile.data.transport.iroh.canonicalToolReturn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Builds a turn's [SettledTurn] from the delta bodies the host relays, which
 * [TurnStreamIdentity] has already stamped with `logical_message_id` / `turn_id`. Text messages
 * keep their last cumulative text; a frame without a stamp is not a message and is ignored.
 * Single-writer: the one coroutine relaying the turn feeds it and reads it after the turn ends.
 */
class SettledTurnCollector(private val fallbackTurnId: String?) {
    private val messages = LinkedHashMap<String, SettledMessage>()
    private var turnId: String? = fallbackTurnId

    /** The sender's prompt, which the host echoes unstamped: its id is the client message id. */
    fun noteUser(clientMessageId: String) {
        messages.getOrPut(clientMessageId) { SettledMessage(clientMessageId, "user_message", otid = clientMessageId) }
        turnId = turnId ?: clientMessageId
    }

    /** Folds one relayed `stream_delta` body (the full frame or the bare delta) into the turn. */
    fun observe(body: String) {
        val delta = deltaOf(body) ?: return
        val logicalId = delta.stringField("logical_message_id") ?: return
        turnId = delta.stringField("turn_id") ?: turnId
        val update = delta.toSettledMessage(logicalId) ?: return
        messages[logicalId] = messages[logicalId]?.mergedWith(update) ?: update
    }

    fun settled(): SettledTurn? = turnId?.let { SettledTurn(it, messages.values.toList()) }

    private fun SettledMessage.mergedWith(next: SettledMessage) = next.copy(
        finalText = next.finalText ?: finalText,
        toolCallId = next.toolCallId ?: toolCallId,
        otid = next.otid ?: otid,
        upstreamId = next.upstreamId ?: upstreamId,
    )
}

private fun deltaOf(body: String): JsonObject? = runCatching {
    val envelope = AppServerProtocol.json.parseToJsonElement(body).jsonObject
    envelope["delta"] as? JsonObject ?: envelope
}.getOrNull()

private fun JsonObject.toSettledMessage(logicalId: String): SettledMessage? {
    val type = stringField("message_type") ?: return null
    return when (type) {
        in TEXT_MESSAGE_TYPES -> SettledMessage(logicalId, type, finalText = messageText(), upstreamId = stringField("id"))
        "user_message" -> SettledMessage(logicalId, type, otid = stringField("otid"))
        "tool_call_message", "approval_request_message" ->
            SettledMessage(logicalId, type, toolCallId = canonicalToolCalls(this).firstOrNull()?.toolCallId)
        "tool_return_message" ->
            SettledMessage(logicalId, type, toolCallId = canonicalToolReturn(this).toolCallId.takeIf { it.isNotBlank() })
        else -> null
    }
}
