package com.letta.mobile.data.transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * letta-mobile-9vgk: wire-level frame types for the admin-shim's
 * `/shim/v1/mobile` WebSocket. Direct port of the contract documented
 * in `admin-shim/docs/MOBILE_WS_PROTOCOL.md` (commit cbe3ed6).
 *
 * **Why a separate envelope layer:** the WS protocol wraps the
 * existing inner [com.letta.mobile.data.model.LettaMessage] payloads
 * with routing metadata (`turn_id`, `run_id`) and adds non-LettaMessage
 * frames (welcome, error, ping, turn_started, stop_reason,
 * usage_statistics, turn_done). Rather than reshape the LettaMessage
 * sealed hierarchy, we keep it intact and define this thin wrapper.
 *
 * **Forward-compat rule (spec §2):** receivers MUST silently ignore
 * unknown `type` values. [ServerFrameSerializer] handles this by
 * returning [ServerFrame.Unknown] for any `type` not in the catalog —
 * callers can pattern-match on `is Unknown` to drop them.
 */

// ─── Client → server ───────────────────────────────────────────────

/**
 * Marker sealed interface for outbound frames. Encoding is handled by
 * [encodeFrame] which dispatches to the per-subtype serializer — using
 * kotlinx.serialization's default polymorphic encoder would conflict
 * with each data class's own `type` field (the protocol locks the
 * field name `type` and we want it to come from the data class, not
 * the polymorphic discriminator).
 */
sealed interface ClientFrame {
    val v: Int
    val type: String
    val id: String
    val ts: String
}

/**
 * Serialize an outbound [ClientFrame] using its concrete subtype's
 * generated serializer. Each branch uses the data-class-declared
 * `type` field as the wire discriminator.
 */
fun ClientFrame.encodeJson(json: Json): String = when (this) {
    is HelloFrame -> json.encodeToString(HelloFrame.serializer(), this)
    is SendMessageFrame -> json.encodeToString(SendMessageFrame.serializer(), this)
    is CancelFrame -> json.encodeToString(CancelFrame.serializer(), this)
    is ByeFrame -> json.encodeToString(ByeFrame.serializer(), this)
    is PongFrame -> json.encodeToString(PongFrame.serializer(), this)
}

@Serializable
data class HelloFrame(
    override val v: Int = 1,
    override val type: String = "hello",
    override val id: String,
    override val ts: String,
    val token: String,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("client_version") val clientVersion: String? = null,
) : ClientFrame

/**
 * lcp-dlj: multimodal sends carry [contentParts] alongside [text].
 *
 * Wire shape mirrors REST `MessageCreate.content` — a JSON array of
 * Letta `LettaMessageContentUnion` parts (text + Anthropic-style
 * base64 image). When [contentParts] is non-null and non-empty the
 * shim ignores [text]; [text] stays required for wire compatibility
 * (older shim builds that don't speak content_parts still need it).
 *
 * Ordering on the wire is insertion order. Canonical builder:
 * `buildContentParts(text, images).toJsonArray()` — `[text-if-any,
 * ...images]`.
 *
 * Image `data` is bare base64 (no `data:` URL prefix); see
 * [com.letta.mobile.data.model.MessageContentPart] for the schema.
 *
 * Shim hard cap: 10 MB JSON-encoded `content_parts` → returns
 * `protocol_violation` (socket stays open, mobile can retry).
 * Mobile soft caps (Anthropic guidance): ≤ 4 images per send, ≤
 * 1568px longest side, ≤ 2 MB raw per image. Downsample on the
 * client to stay under the hard cap.
 */
@Serializable
data class SendMessageFrame(
    override val v: Int = 1,
    override val type: String = "send_message",
    override val id: String,
    override val ts: String,
    @SerialName("agent_id") val agentId: String,
    @SerialName("conversation_id") val conversationId: String,
    val text: String,
    val otid: String? = null,
    @SerialName("content_parts") val contentParts: JsonArray? = null,
) : ClientFrame

/**
 * Spec §2.1 / §4.6: `run_id` is REQUIRED. The shim does NOT fall back
 * to "current run" — sending without it is a `protocol_violation`
 * even though the socket stays open.
 */
@Serializable
data class CancelFrame(
    override val v: Int = 1,
    override val type: String = "cancel",
    override val id: String,
    override val ts: String,
    @SerialName("run_id") val runId: String,
) : ClientFrame

@Serializable
data class ByeFrame(
    override val v: Int = 1,
    override val type: String = "bye",
    override val id: String,
    override val ts: String,
) : ClientFrame

@Serializable
data class PongFrame(
    override val v: Int = 1,
    override val type: String = "pong",
    override val id: String,
    override val ts: String,
) : ClientFrame

// ─── Server → client ───────────────────────────────────────────────

/**
 * Sealed catalog of every frame the shim emits. Discriminated by the
 * `type` field via [ServerFrameSerializer].
 */
@Serializable(with = ServerFrameSerializer::class)
sealed interface ServerFrame {
    val v: Int
    val id: String
    val ts: String

    @Serializable
    data class Welcome(
        override val v: Int = 1,
        val type: String = "welcome",
        override val id: String,
        override val ts: String,
        @SerialName("server_id") val serverId: String,
        @SerialName("session_id") val sessionId: String,
        @SerialName("device_id") val deviceId: String? = null,
    ) : ServerFrame

    /**
     * Spec §7: `code` ∈ invalid_token | protocol_violation |
     * agent_not_found | conversation_not_found | run_not_found |
     * internal_error. `turn_id` / `run_id` are populated only for
     * mid-turn errors (e.g. internal_error).
     */
    @Serializable
    data class Error(
        override val v: Int = 1,
        val type: String = "error",
        override val id: String,
        override val ts: String,
        val code: String,
        val message: String = "",
        @SerialName("turn_id") val turnId: String? = null,
        @SerialName("run_id") val runId: String? = null,
    ) : ServerFrame

    @Serializable
    data class Ping(
        override val v: Int = 1,
        val type: String = "ping",
        override val id: String,
        override val ts: String,
    ) : ServerFrame

    /**
     * lcp-99a: the shim pre-creates the Run synchronously before emitting
     * turn_started, so `run_id` is always present from the very first
     * frame of the turn. Mobile may treat null as a shim regression and
     * crash-loud rather than fall back.
     */
    @Serializable
    data class TurnStarted(
        override val v: Int = 1,
        val type: String = "turn_started",
        override val id: String,
        override val ts: String,
        @SerialName("agent_id") val agentId: String,
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("turn_id") val turnId: String,
        @SerialName("run_id") val runId: String,
    ) : ServerFrame

    /**
     * lcp-srk: `lossy` flips true if at least one frame was dropped at
     * the shim's backpressure gate (default 1 MB bufferedAmount). Mobile
     * should reconcile from disk only when `lossy == true`. `dropCount`
     * is informational telemetry — don't branch on its value.
     */
    @Serializable
    data class TurnDone(
        override val v: Int = 1,
        val type: String = "turn_done",
        override val id: String,
        override val ts: String,
        @SerialName("turn_id") val turnId: String,
        @SerialName("run_id") val runId: String,
        val status: String, // "completed" | "cancelled" | "failed"
        val lossy: Boolean = false,
        @SerialName("drop_count") val dropCount: Long = 0L,
    ) : ServerFrame

    /**
     * Spec §4.7: bare-envelope shape — the inner field is `stop_reason`
     * (NOT `reason`), matching the SSE/REST emit and the existing
     * Kotlin [com.letta.mobile.data.model.StopReasonMessage]. The WS
     * envelope adds `turn_id` / `run_id` for routing.
     */
    @Serializable
    data class StopReason(
        override val v: Int = 1,
        val type: String = "stop_reason",
        override val id: String,
        override val ts: String,
        @SerialName("turn_id") val turnId: String,
        @SerialName("run_id") val runId: String,
        @SerialName("stop_reason") val stopReason: String,
    ) : ServerFrame

    /**
     * Spec §4.4: this is the FIRST `usage_statistics` of the turn,
     * NOT a sum. Multi-step turns may produce per-step usage; the
     * run-level record reflects the first.
     */
    @Serializable
    data class UsageStatistics(
        override val v: Int = 1,
        val type: String = "usage_statistics",
        override val id: String,
        override val ts: String,
        @SerialName("turn_id") val turnId: String,
        @SerialName("run_id") val runId: String,
        @SerialName("prompt_tokens") val promptTokens: Long = 0,
        @SerialName("completion_tokens") val completionTokens: Long = 0,
        @SerialName("total_tokens") val totalTokens: Long = 0,
        @SerialName("cached_input_tokens") val cachedInputTokens: Long = 0,
        @SerialName("reasoning_tokens") val reasoningTokens: Long = 0,
    ) : ServerFrame

    /**
     * Spec §2.2 + §4.2: `id` always carries the `cm-stream-` prefix.
     * `otid` echoes the client's [SendMessageFrame.otid] when present
     * so mobile's `dedupeOptimisticContentTwins` can collapse the
     * stream-vs-disk twins on reconcile.
     */
    @Serializable
    data class AssistantMessage(
        override val v: Int = 1,
        val type: String = "assistant_message",
        override val id: String,
        override val ts: String,
        @SerialName("agent_id") val agentId: String,
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("turn_id") val turnId: String,
        @SerialName("run_id") val runId: String,
        val content: String,
        val otid: String? = null,
    ) : ServerFrame

    @Serializable
    data class ReasoningMessage(
        override val v: Int = 1,
        val type: String = "reasoning_message",
        override val id: String,
        override val ts: String,
        @SerialName("agent_id") val agentId: String,
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("turn_id") val turnId: String,
        @SerialName("run_id") val runId: String,
        val reasoning: String,
        val signature: String? = null,
    ) : ServerFrame

    /**
     * Spec §4.1: every approval_request_message from letta-code is
     * remapped to tool_call_message before reaching the wire. Mobile
     * MUST treat both as interchangeable for display. The envelope `id`
     * is `toolcall-${tool_call_id}` (not a random UUID) — locked
     * contract. Both `tool_call` (singular) and `tool_calls` (array)
     * carry the same content; this type exposes both for
     * forward-compat with consumers that prefer one or the other.
     */
    @Serializable
    data class ToolCallMessage(
        override val v: Int = 1,
        val type: String = "tool_call_message",
        override val id: String,
        override val ts: String,
        @SerialName("agent_id") val agentId: String,
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("turn_id") val turnId: String,
        @SerialName("run_id") val runId: String,
        @SerialName("tool_call") val toolCall: ToolCallPayload? = null,
        @SerialName("tool_calls") val toolCalls: List<ToolCallPayload>? = null,
    ) : ServerFrame

    @Serializable
    data class ToolReturnMessage(
        override val v: Int = 1,
        val type: String = "tool_return_message",
        override val id: String,
        override val ts: String,
        @SerialName("agent_id") val agentId: String,
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("turn_id") val turnId: String,
        @SerialName("run_id") val runId: String,
        @SerialName("tool_call_id") val toolCallId: String,
        val status: String = "success",
        @SerialName("tool_return") val toolReturn: String? = null,
        val stdout: List<String>? = null,
        val stderr: List<String>? = null,
    ) : ServerFrame

    /**
     * Forward-compat sink for unknown `type` values. Spec §2 mandates
     * silent-ignore; surfacing as a typed value (rather than throwing)
     * lets callers tell apart "deserialization failed" from "frame the
     * shim added in a newer protocol version we don't speak yet".
     *
     * Deserialized via [UnknownFrameDeserializer] — we capture the full
     * envelope as `raw` so future code can introspect without
     * round-tripping through JSON again.
     */
    data class Unknown(
        override val v: Int = 1,
        override val id: String = "",
        override val ts: String = "",
        val type: String,
        val raw: JsonObject,
    ) : ServerFrame
}

@Serializable
data class ToolCallPayload(
    @SerialName("tool_call_id") val toolCallId: String,
    val name: String,
    val arguments: String,
)

/**
 * Discriminated-union deserializer for [ServerFrame], keyed on the
 * envelope `type` field. Matches the pattern used by
 * [com.letta.mobile.data.model.LettaMessageSerializer] for the inner
 * LettaMessage hierarchy.
 *
 * Unknown `type` values fall through to [ServerFrame.Unknown] so the
 * forward-compat silent-ignore rule (spec §2) is honored without
 * losing visibility into what the shim sent.
 */
object ServerFrameSerializer : JsonContentPolymorphicSerializer<ServerFrame>(ServerFrame::class) {
    override fun selectDeserializer(element: JsonElement): kotlinx.serialization.DeserializationStrategy<ServerFrame> {
        val type = element.jsonObject["type"]?.jsonPrimitive?.content
        return when (type) {
            "welcome" -> ServerFrame.Welcome.serializer()
            "error" -> ServerFrame.Error.serializer()
            "ping" -> ServerFrame.Ping.serializer()
            "turn_started" -> ServerFrame.TurnStarted.serializer()
            "turn_done" -> ServerFrame.TurnDone.serializer()
            "stop_reason" -> ServerFrame.StopReason.serializer()
            "usage_statistics" -> ServerFrame.UsageStatistics.serializer()
            "assistant_message" -> ServerFrame.AssistantMessage.serializer()
            "reasoning_message" -> ServerFrame.ReasoningMessage.serializer()
            "tool_call_message",
            "approval_request_message" -> ServerFrame.ToolCallMessage.serializer()
            "tool_return_message" -> ServerFrame.ToolReturnMessage.serializer()
            else -> UnknownFrameDeserializer
        }
    }
}

/**
 * Deserialize-only stand-in for the polymorphic strategy when the
 * envelope carries a `type` we don't recognize. Pulls the raw element
 * out of the [kotlinx.serialization.json.JsonDecoder] and packs it
 * into [ServerFrame.Unknown] without trying to map any individual
 * fields (we don't know the shape). Implements the full [KSerializer]
 * surface because [JsonContentPolymorphicSerializer.selectDeserializer]
 * casts its return value to KSerializer at runtime.
 */
private object UnknownFrameDeserializer : kotlinx.serialization.KSerializer<ServerFrame> {
    override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor =
        kotlinx.serialization.descriptors.buildClassSerialDescriptor("ServerFrame.Unknown")

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): ServerFrame {
        val jsonDecoder = decoder as? kotlinx.serialization.json.JsonDecoder
            ?: error("UnknownFrameDeserializer requires a JsonDecoder")
        val element = jsonDecoder.decodeJsonElement().jsonObject
        return ServerFrame.Unknown(
            v = element["v"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
            id = element["id"]?.jsonPrimitive?.content.orEmpty(),
            ts = element["ts"]?.jsonPrimitive?.content.orEmpty(),
            type = element["type"]?.jsonPrimitive?.content.orEmpty(),
            raw = element,
        )
    }

    /**
     * We never need to serialize an [ServerFrame.Unknown] (it's
     * inbound-only — the client never produces unknown server
     * frames). Throwing here makes any accidental encode loud.
     */
    override fun serialize(
        encoder: kotlinx.serialization.encoding.Encoder,
        value: ServerFrame,
    ): Unit = error("ServerFrame.Unknown is inbound-only; encoding it is never valid")
}
