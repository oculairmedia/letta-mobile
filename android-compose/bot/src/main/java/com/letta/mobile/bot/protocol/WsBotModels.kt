package com.letta.mobile.bot.protocol

import com.letta.mobile.data.a2ui.A2uiCapabilityDeclaration
import com.letta.mobile.data.a2ui.A2uiMessage
import com.letta.mobile.data.a2ui.A2uiNegotiation
import com.letta.mobile.data.model.ToolCall
import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Per-request state shared between the WS reader coroutine and the
 * `streamMessage` flow consumer.
 *
 * Threading model
 * ---------------
 * Today the gateway enforces a single in-flight request at a time:
 * `requestMutex` (see `WsBotProtocol.streamMessage` / `promoteRoute`) serializes
 * the body of each stream and is the writer-side guarantee that `conversationId`
 * and `firstFrameSent` cannot be mutated concurrently while a request is alive.
 *
 * Readers, however, are not all on the writer's coroutine — most notably
 * `soleInFlightRouteOrNull` filters routes by `firstFrameSent` from a different
 * coroutine context. The `@Volatile` annotations exist so that those
 * cross-thread reads see the writer's most recent value (happens-before via
 * volatile semantics), even though the writer side is already serialized.
 *
 * If/when the design switches to concurrent in-flight streams, both fields
 * must move behind an explicit `Mutex` (or be replaced with `AtomicReference`)
 * — `@Volatile` alone is not sufficient for compound read-modify-write.
 */
internal data class RequestRoute(
    val requestId: String,
    val channel: SendChannel<RequestSignal>,
    @Volatile var conversationId: String?,
    @Volatile var firstFrameSent: Boolean = false,
)

internal sealed interface RequestSignal {
    data class Message(val message: WsInboundMessage) : RequestSignal
    data class Failure(val throwable: Throwable) : RequestSignal
}

internal sealed interface WsInboundMessage

@Serializable
internal data class WsSessionStart(
    val type: String = "session_start",
    @SerialName("agent_id") val agentId: String,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("force_new") val forceNew: Boolean = false,
    @SerialName("a2ui_capability") val a2uiCapability: A2uiCapabilityDeclaration? = A2uiCapabilityDeclaration(),
)

@Serializable
internal data class WsClientMessage(
    val type: String = "message",
    val content: String,
    @SerialName("request_id") val requestId: String,
    val source: WsSource,
)

@Serializable
internal data class WsSource(
    val channel: String,
    val chatId: String,
)

@Serializable
internal data class WsAbortMessage(
    val type: String = "abort",
    @SerialName("request_id") val requestId: String,
)

@Serializable
internal data class WsSessionCloseMessage(
    val type: String = "session_close",
)

@Serializable
internal data class WsSessionInit(
    val type: String,
    @SerialName("agent_id") val agentId: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("a2ui_negotiation") val a2uiNegotiation: A2uiNegotiation? = null,
) : WsInboundMessage

internal data class WsA2uiMessage(
    val type: String = "a2ui",
    val messages: List<A2uiMessage>,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    val raw: JsonObject,
) : WsInboundMessage

@Serializable
internal data class WsStreamEventMessage(
    val type: String,
    val event: BotStreamEvent,
    val content: String? = null,
    @SerialName("tool_name") val toolName: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_input") val toolInput: JsonElement? = null,
    @SerialName("tool_calls")
    @Serializable(with = BotToolCallListSerializer::class)
    val toolCalls: List<ToolCall>? = null,
    @SerialName("is_error") val isError: Boolean = false,
    val uuid: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    /**
     * letta-mobile-flk.5: present when the gateway echoes the active
     * Letta conversation id on each per-chunk frame, OR when the
     * gateway emits an explicit `conversation_swap` event (in which
     * case this carries the *new* conversation id). Falling back to
     * the connection-level `activeConversationId` when absent
     * preserves bug-for-bug behavior with older gateway builds.
     */
    @SerialName("conversation_id") val conversationId: String? = null,
    /**
     * letta-mobile-flk.5: present only on
     * `event == CONVERSATION_SWAP` — the conversation id the gateway
     * abandoned. Allows the receiver to migrate stranded optimistic
     * locals from the old timeline before re-pointing the observer.
     */
    @SerialName("old_conversation_id") val oldConversationId: String? = null,
) : WsInboundMessage

@Serializable
internal data class WsResultMessage(
    val type: String,
    val success: Boolean,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val error: String? = null,
    val aborted: Boolean = false,
) : WsInboundMessage

@Serializable
internal data class WsErrorMessage(
    val type: String,
    val code: String,
    val message: String,
    @SerialName("request_id") val requestId: String? = null,
) : WsInboundMessage
