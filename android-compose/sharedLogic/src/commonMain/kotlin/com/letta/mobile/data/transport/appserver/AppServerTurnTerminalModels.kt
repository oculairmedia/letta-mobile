package com.letta.mobile.data.transport.appserver

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * The `runtime` of a `turn_finished_ack` (letta-code 0.34+). Unlike [AppServerRuntimeScope],
 * upstream requires `agent_id` to be present (`string | null`): an agent-free conversation sends
 * an explicit `null`, never an absent key, so this scope has its own serializer.
 */
@Serializable(with = AppServerTerminalAckScopeSerializer::class)
data class AppServerTerminalAckScope(
    val agentId: String?,
    val conversationId: String,
) {
    companion object {
        fun of(scope: AppServerRuntimeScope): AppServerTerminalAckScope =
            AppServerTerminalAckScope(
                agentId = scope.agentId.takeUnless { scope.isAgentFree },
                conversationId = scope.conversationId,
            )
    }
}

internal object AppServerTerminalAckScopeSerializer : KSerializer<AppServerTerminalAckScope> {
    private val delegate = JsonObject.serializer()

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: AppServerTerminalAckScope) {
        encoder.encodeSerializableValue(
            delegate,
            buildJsonObject {
                put("agent_id", value.agentId?.let(::JsonPrimitive) ?: JsonNull)
                put("conversation_id", JsonPrimitive(value.conversationId))
            },
        )
    }

    override fun deserialize(decoder: Decoder): AppServerTerminalAckScope {
        val raw = decoder.decodeSerializableValue(delegate)
        val conversationId = (raw["conversation_id"] as? JsonPrimitive)?.contentOrNull
            ?: throw SerializationException("turn_finished_ack runtime has no conversation_id")
        return AppServerTerminalAckScope(
            agentId = (raw["agent_id"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull,
            conversationId = conversationId,
        )
    }
}

/**
 * letta-code 0.34+ `isTerminalConsumerId`: the id a client names itself by in
 * `input.terminal_consumer_id` and `turn_finished_ack.consumer_id`. Upstream rejects the whole
 * command when the id is malformed, so callers check it before sending.
 */
object AppServerTerminalConsumerId {
    const val MAX_LENGTH: Int = 256

    private val ALLOWED = Regex("^[A-Za-z0-9._~:%-]+$")

    fun isValid(value: String): Boolean = value.length in 1..MAX_LENGTH && ALLOWED.matches(value)
}
