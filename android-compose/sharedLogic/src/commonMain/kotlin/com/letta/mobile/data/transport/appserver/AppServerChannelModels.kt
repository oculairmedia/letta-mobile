package com.letta.mobile.data.transport.appserver

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject

@Serializable
enum class AppServerChannel {
    @SerialName("control")
    Control,

    @SerialName("stream")
    Stream,
}

@Serializable
data class AppServerReceivedFrame(
    val channel: AppServerChannel,
    val frame: AppServerInboundFrame,
    val raw: JsonObject,
    /**
     * Connection generation that produced this frame (lgns8.22.4). Stamped when
     * the frame enters a stable reconnect pipe so delayed delivery cannot
     * register under a successor generation. Null for transports that do not
     * stamp (tests / direct clients) — callers fall back to the live provider.
     */
    @Transient val connectionGeneration: Long? = null,
)

/**
 * A runtime's `{agent_id, conversation_id}` scope.
 *
 * letta-mobile-bzvro.10 (F10): letta-code 0.33 widened inbound scopes to
 * `ConversationRuntimeScope`, whose `agent_id` may be `null` for agent-free conversations. Strict
 * decoding turned every such frame (stream deltas, loop status, queue, `turn_finished`) into a
 * `DecodeFailure`. A null or absent `agent_id` now decodes to [AGENT_FREE] (the empty id), so the
 * frame stays typed and every map keyed on `(agentId, conversationId)` keeps one stable key for the
 * conversation. An agent-free scope encodes its `agent_id` back as `null`, as upstream expects.
 */
@Serializable(with = AppServerRuntimeScopeSerializer::class)
data class AppServerRuntimeScope(
    val agentId: String,
    val conversationId: String,
    val actingUserId: String? = null,
) {
    /** The scope names a conversation with no agent (letta-code 0.33+). */
    val isAgentFree: Boolean get() = agentId == AGENT_FREE

    companion object {
        /** The [agentId] of an agent-free scope. */
        const val AGENT_FREE: String = ""
    }
}

/** The wire shape of [AppServerRuntimeScope]; `agent_id` is nullable from letta-code 0.33. */
@Serializable
private data class AppServerRuntimeScopeWire(
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("acting_user_id") val actingUserId: String? = null,
)

internal object AppServerRuntimeScopeSerializer : KSerializer<AppServerRuntimeScope> {
    private val wire = AppServerRuntimeScopeWire.serializer()

    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        SerialDescriptor("com.letta.mobile.data.transport.appserver.AppServerRuntimeScope", wire.descriptor)

    override fun serialize(encoder: Encoder, value: AppServerRuntimeScope) {
        encoder.encodeSerializableValue(
            wire,
            AppServerRuntimeScopeWire(
                agentId = value.agentId.takeUnless { value.isAgentFree },
                conversationId = value.conversationId,
                actingUserId = value.actingUserId,
            ),
        )
    }

    override fun deserialize(decoder: Decoder): AppServerRuntimeScope {
        val decoded = decoder.decodeSerializableValue(wire)
        return AppServerRuntimeScope(
            agentId = decoded.agentId ?: AppServerRuntimeScope.AGENT_FREE,
            conversationId = decoded.conversationId,
            actingUserId = decoded.actingUserId,
        )
    }
}

@Serializable
enum class AppServerPermissionMode {
    @SerialName("standard")
    Standard,

    @SerialName("acceptEdits")
    AcceptEdits,

    @SerialName("strict")
    Strict,

    @SerialName("unrestricted")
    Unrestricted,

    ;

    companion object {
        fun fromWireValue(value: String): AppServerPermissionMode? = when (value) {
            "standard" -> Standard
            "acceptEdits" -> AcceptEdits
            "strict" -> Strict
            "unrestricted" -> Unrestricted
            else -> null
        }
    }
}

@Serializable
data class AppServerRuntimeStartClientInfo(
    val name: String,
    val title: String? = null,
    val version: String? = null,
)

@Serializable
data class AppServerRuntimeStartCreateAgentOptions(
    val body: JsonObject,
    @SerialName("pin_global") val pinGlobal: Boolean? = null,
)

@Serializable
data class AppServerRuntimeStartCreateConversationOptions(
    val body: JsonObject? = null,
)

@Serializable
data class AppServerExternalToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val label: String? = null,
)

@Serializable
data class AppServerExternalToolsGroup(
    @SerialName("scope_id") val scopeId: String? = null,
    val tools: List<AppServerExternalToolDefinition>,
)
