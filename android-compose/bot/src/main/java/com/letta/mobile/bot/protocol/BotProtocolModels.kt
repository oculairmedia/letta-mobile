package com.letta.mobile.bot.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
data class BotChatRequest(
    val message: String,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("chat_id") val chatId: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("sender_name") val senderName: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    /**
     * letta-mobile-flk.6: when true, the gateway is told to clear its persisted
     * conversation mapping for this agent and start a fresh conversation on
     * the Letta server. Mirrors the gateway's WS `session_start.force_new`
     * field. Set this when the user explicitly opens a "New chat" route —
     * otherwise lettabot will auto-resume the previous conversation for the
     * agent and the new-chat UI will silently continue an old thread.
     */
    @SerialName("force_new") val forceNew: Boolean = false,
    @Transient val contentItems: List<BotMessageContentItem>? = null,
)

@Serializable
data class BotChatResponse(
    val response: String,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
)

@Serializable
data class BotStatusResponse(
    val status: String = "ok",
    @Serializable(with = BotStatusAgentsSerializer::class)
    val agents: List<String> = emptyList(),
    @SerialName("session_count") val sessionCount: Int = 0,
    @SerialName("agent_details") val agentDetails: List<BotAgentInfo> = emptyList(),
    @SerialName("active_profile_ids") val activeProfileIds: List<String> = emptyList(),
    @SerialName("active_modes") val activeModes: List<String> = emptyList(),
    @SerialName("api_port") val apiPort: Int? = null,
    @SerialName("auth_required") val authRequired: Boolean = false,
    @SerialName("rate_limit_requests") val rateLimitRequests: Int = 0,
    @SerialName("rate_limit_window_seconds") val rateLimitWindowSeconds: Long = 0,
)

fun BotStatusResponse.preferredAgent(preferredName: String = "LettaBot"): BotAgentInfo? {
    if (agentDetails.isNotEmpty()) {
        return agentDetails.firstOrNull { it.name == preferredName }
            ?: agentDetails.first()
    }

    if (agents.isEmpty()) return null
    val resolvedName = agents.firstOrNull { it == preferredName } ?: agents.first()
    return BotAgentInfo(
        id = resolvedName,
        name = resolvedName,
        status = status,
    )
}

@Serializable
data class BotAgentInfo(
    val id: String,
    val name: String,
    val status: String,
)

object BotStatusAgentsSerializer : KSerializer<List<String>> {
    private val listDelegate = ListSerializer(String.serializer())

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("BotStatusAgents")

    override fun deserialize(decoder: Decoder): List<String> {
        val jd = decoder as? JsonDecoder
            ?: error("BotStatusAgentsSerializer only supports JSON")
        val element = jd.decodeJsonElement()
        return when (element) {
            is JsonArray -> jd.json.decodeFromJsonElement(listDelegate, element)
            is JsonObject -> element.keys.toList()
            else -> emptyList()
        }
    }

    override fun serialize(encoder: Encoder, value: List<String>) {
        val je = encoder as? JsonEncoder
            ?: error("BotStatusAgentsSerializer only supports JSON")
        je.encodeSerializableValue(listDelegate, value)
    }
}

object BotStatusResponseParser {
    private val agentInfoListSerializer = ListSerializer(BotAgentInfo.serializer())

    fun parse(json: kotlinx.serialization.json.Json, element: JsonElement): BotStatusResponse {
        val obj = element as? JsonObject ?: return BotStatusResponse()
        val agentsElement = obj["agents"]
        val parsedAgents = parseAgentNames(json, agentsElement)
        val parsedAgentDetails = parseAgentDetails(json, agentsElement)
        val explicitAgentDetails = parseExplicitAgentDetails(json, obj["agent_details"])

        return BotStatusResponse(
            status = obj.stringValue("status") ?: "ok",
            agents = parsedAgents,
            sessionCount = obj.intValue("session_count") ?: 0,
            agentDetails = explicitAgentDetails.ifEmpty { parsedAgentDetails },
            activeProfileIds = obj.stringListValue(json, "active_profile_ids"),
            activeModes = obj.stringListValue(json, "active_modes"),
            apiPort = obj.intValue("api_port"),
            authRequired = obj.booleanValue("auth_required") ?: false,
            rateLimitRequests = obj.intValue("rate_limit_requests") ?: 0,
            rateLimitWindowSeconds = obj.longValue("rate_limit_window_seconds") ?: 0L,
        )
    }

    private fun parseAgentNames(
        json: kotlinx.serialization.json.Json,
        agentsElement: JsonElement?,
    ): List<String> = when (agentsElement) {
        is JsonArray -> json.decodeFromJsonElement(ListSerializer(String.serializer()), agentsElement)
        is JsonObject -> agentsElement.keys.toList()
        else -> emptyList()
    }

    private fun parseExplicitAgentDetails(
        json: kotlinx.serialization.json.Json,
        detailsElement: JsonElement?,
    ): List<BotAgentInfo> = when (detailsElement) {
        is JsonArray -> json.decodeFromJsonElement(agentInfoListSerializer, detailsElement)
        else -> emptyList()
    }

    private fun parseAgentDetails(
        json: kotlinx.serialization.json.Json,
        agentsElement: JsonElement?,
    ): List<BotAgentInfo> = when (agentsElement) {
        is JsonObject -> agentsElement.mapNotNull { (name, value) ->
            val details = value as? JsonObject ?: return@mapNotNull null
            val id = details.stringValue("agentId")
                ?: details.stringValue("agent_id")
                ?: details.stringValue("id")
                ?: return@mapNotNull null
            BotAgentInfo(
                id = id,
                name = name,
                status = details.stringValue("status") ?: "ready",
            )
        }

        is JsonArray -> json.decodeFromJsonElement(agentInfoListSerializer, agentsElement)
        else -> emptyList()
    }

    private fun JsonObject.stringListValue(
        json: kotlinx.serialization.json.Json,
        key: String,
    ): List<String> = when (val element = this[key]) {
        is JsonArray -> json.decodeFromJsonElement(ListSerializer(String.serializer()), element)
        else -> emptyList()
    }

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.intValue(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.longValue(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.booleanValue(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull
}

@Serializable
data class BotStreamChunk(
    val text: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    val event: BotStreamEvent? = null,
    @SerialName("tool_name") val toolName: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_input") val toolInput: JsonElement? = null,
    @SerialName("is_error") val isError: Boolean = false,
    @SerialName("request_id") val requestId: String? = null,
    val uuid: String? = null,
    val aborted: Boolean = false,
    val done: Boolean = false,

    /**
     * letta-mobile-flk.5: present only on `event == CONVERSATION_SWAP`.
     * The conversation id the client originally requested (the one that
     * the gateway abandoned). Receivers can use this to assert the
     * swap is for the conv they care about, and to pull stranded
     * optimistic locals from the old timeline before the observer is
     * re-pointed at the new conversation.
     */
    @SerialName("old_conversation_id") val oldConversationId: String? = null,
)

@Serializable
data class BotErrorResponse(
    val error: String,
)

@Serializable
enum class BotStreamEvent {
    @SerialName("assistant") ASSISTANT,
    @SerialName("tool_call") TOOL_CALL,
    @SerialName("tool_result") TOOL_RESULT,
    @SerialName("reasoning") REASONING,

    /**
     * letta-mobile-flk.5: gateway-emitted notification that the in-flight
     * request was retried on a fresh Letta conversation because the
     * original conversation was unrecoverable (typically a stuck
     * `requires_approval` or stale conversation state).
     *
     * Wire shape: `{ type: "stream", event: "conversation_swap",
     *   old_conversation_id, conversation_id, request_id }`. The new
     * conv id arrives in the standard `conversation_id` field on
     * `BotStreamChunk` so existing swap-detection logic in
     * `AdminChatViewModel` (which keys off `chunk.conversationId`) sees
     * the change at the same moment as this explicit signal.
     *
     * Receivers should switch their timeline observer to the new
     * conversation, migrate any optimistic local message that was
     * appended to the old conversation, and update navigation state.
     */
    @SerialName("conversation_swap") CONVERSATION_SWAP,
}

enum class ConnectionState {
    CLOSED,
    CONNECTING,
    READY,
    PROCESSING,
    RECONNECTING,
}

enum class BotGatewayErrorCode {
    AUTH_FAILED,
    BAD_MESSAGE,
    NO_SESSION,
    SESSION_BUSY,
    SESSION_INIT_FAILED,
    STREAM_ERROR,
}

class BotGatewayException(
    val code: BotGatewayErrorCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

@Serializable
data class BotMessageContentItem(
    val type: String,
    val text: String? = null,
    val source: BotMessageContentSource? = null,
) {
    companion object {
        fun text(text: String): BotMessageContentItem = BotMessageContentItem(
            type = "text",
            text = text,
        )

        fun image(
            base64: String,
            mediaType: String,
            sourceType: String = "base64",
        ): BotMessageContentItem = BotMessageContentItem(
            type = "image",
            source = BotMessageContentSource(
                type = sourceType,
                mediaType = mediaType,
                data = base64,
            ),
        )
    }
}

@Serializable
data class BotMessageContentSource(
    val type: String = "base64",
    @SerialName("media_type") val mediaType: String,
    val data: String,
)
