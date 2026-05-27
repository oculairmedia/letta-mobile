package com.letta.mobile.data.transport

import com.letta.mobile.data.a2ui.A2UI_DEFAULT_SUPPORTED_CATALOGS
import com.letta.mobile.data.a2ui.A2UI_DEFAULT_SUPPORTED_WIDGETS
import com.letta.mobile.data.a2ui.A2UI_HELLO_VERSION
import com.letta.mobile.data.a2ui.A2uiHandshakeAck
import com.letta.mobile.data.a2ui.A2uiMessage
import com.letta.mobile.data.a2ui.A2uiThemeHints
import com.letta.mobile.data.a2ui.decodeA2uiMessages
import com.letta.mobile.data.model.CronTask
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
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
    is UserActionFrame -> json.encodeToString(UserActionFrame.serializer(), this)
    is CancelFrame -> json.encodeToString(CancelFrame.serializer(), this)
    is SubscribeFrame -> json.encodeToString(SubscribeFrame.serializer(), this)
    is ByeFrame -> json.encodeToString(ByeFrame.serializer(), this)
    is PongFrame -> json.encodeToString(PongFrame.serializer(), this)
    is CronListFrame -> json.encodeToString(CronListFrame.serializer(), this)
    is CronAddFrame -> json.encodeToString(CronAddFrame.serializer(), this)
    is CronGetFrame -> json.encodeToString(CronGetFrame.serializer(), this)
    is CronDeleteFrame -> json.encodeToString(CronDeleteFrame.serializer(), this)
    is CronDeleteAllFrame -> json.encodeToString(CronDeleteAllFrame.serializer(), this)
}

/**
 * Spec §2.1 hello — capability fields are TOP-LEVEL, not nested.
 * The shim does string-equality on `a2ui_version` against its
 * server-side A2UI_VERSION env (no `v` prefix). `supported_catalogs`
 * must include `"basic"` (the shim's only v1 catalog handle, NOT
 * the upstream `$id` URL). `supported_widgets` is informational —
 * send the union of what the renderer actually mounts.
 */
@Serializable
data class HelloFrame(
    override val v: Int = 1,
    override val type: String = "hello",
    override val id: String,
    override val ts: String,
    val token: String,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("client_version") val clientVersion: String? = null,
    @SerialName("a2ui_version") val a2uiVersion: String = A2UI_HELLO_VERSION,
    @SerialName("supported_catalogs") val supportedCatalogs: List<String> = A2UI_DEFAULT_SUPPORTED_CATALOGS,
    @SerialName("supported_widgets") val supportedWidgets: List<String> = A2UI_DEFAULT_SUPPORTED_WIDGETS,
    @SerialName("theme_hints") val themeHints: A2uiThemeHints = A2uiThemeHints(),
    val resume: List<ResumeCursor>? = null,
) : ClientFrame

@Serializable
data class ResumeCursor(
    @SerialName("conv_id") val conversationId: String,
    @SerialName("after_seq") val afterSeq: Long,
)

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
    @SerialName("start_new_conversation") val startNewConversation: Boolean = false,
    val text: String,
    val otid: String? = null,
    @SerialName("content_parts") val contentParts: JsonArray? = null,
) : ClientFrame

/**
 * A2UI user interaction frame (§2.1). The renderer resolves every
 * declared context binding before this reaches the wire.
 *
 * Wire shape matches shim's MOBILE_WS_PROTOCOL.md:
 *   { type:"user_action", name:"…", context:{…},
 *     run_id?:…, turn_id?:…, surface_id?:…, action_id?:… }
 *
 * `context` MUST be a JsonObject — the shim rejects arrays/primitives.
 */
@Serializable
data class UserActionFrame(
    override val v: Int = 1,
    override val type: String = "user_action",
    override val id: String,
    override val ts: String,
    val name: String,
    val context: JsonObject = JsonObject(emptyMap()),
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("run_id") val runId: String? = null,
    @SerialName("turn_id") val turnId: String? = null,
    @SerialName("surface_id") val surfaceId: String? = null,
    @SerialName("action_id") val actionId: String? = null,
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

/**
 * Spec §3.4 + §11: replay + live-tail a Run's frame log. Used to resume
 * a turn after disconnect or to observe a run started by another client.
 *
 * `cursor` is the last `seq` the client has already received; the server
 * emits only frames with `seq > cursor`. Pass `0` for a full replay.
 * Server responds with `subscribe_frame` per missed frame, then
 * `subscribe_done` once the run reaches terminal status. letta-mobile-2rkdj.
 */
@Serializable
data class SubscribeFrame(
    override val v: Int = 1,
    override val type: String = "subscribe",
    override val id: String,
    override val ts: String,
    @SerialName("run_id") val runId: String,
    val cursor: Long = 0L,
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

// ─── Cron client frames (letta-mobile-d52f.1, sister to lcp-d5g) ───
//
// All cron client frames carry a `request_id` field that is independent
// of the envelope `id`. The server echoes the same `request_id` on the
// matching response so the repo layer can route an awaited continuation
// to its caller instead of broadcasting to every observer. Generate via
// UUID alongside the envelope id.

/**
 * Read-only enumeration of cron tasks. Both filters are optional: when
 * `agentId` is null the server returns every task; when non-null, only
 * tasks for that agent. `conversationId` further narrows within an
 * agent.
 */
@Serializable
data class CronListFrame(
    override val v: Int = 1,
    override val type: String = "cron_list",
    override val id: String,
    override val ts: String,
    @SerialName("request_id") val requestId: String,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
) : ClientFrame

/**
 * Add a scheduled prompt. `recurring` distinguishes a once-only task
 * from a repeating one. Exactly one of `cron` / `every` / `at` should be
 * populated:
 *   - `cron` — raw 5-field cron expression (e.g. `"0 9 * * 1-5"`).
 *   - `every` — human-friendly interval shorthand (e.g. `"5m"`, `"1h"`).
 *   - `at` — one-shot time (e.g. `"3:00pm"`, `"in 45m"`).
 * The shim normalizes all three into the persisted `cron` field; the
 * client picks whichever form the user expressed.
 */
@Serializable
data class CronAddFrame(
    override val v: Int = 1,
    override val type: String = "cron_add",
    override val id: String,
    override val ts: String,
    @SerialName("request_id") val requestId: String,
    @SerialName("agent_id") val agentId: String,
    val name: String,
    val description: String,
    val prompt: String,
    val recurring: Boolean,
    val cron: String? = null,
    val every: String? = null,
    val at: String? = null,
    val timezone: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
) : ClientFrame

@Serializable
data class CronGetFrame(
    override val v: Int = 1,
    override val type: String = "cron_get",
    override val id: String,
    override val ts: String,
    @SerialName("request_id") val requestId: String,
    @SerialName("task_id") val taskId: String,
) : ClientFrame

@Serializable
data class CronDeleteFrame(
    override val v: Int = 1,
    override val type: String = "cron_delete",
    override val id: String,
    override val ts: String,
    @SerialName("request_id") val requestId: String,
    @SerialName("task_id") val taskId: String,
) : ClientFrame

/**
 * Delete every task for a given agent. The shim returns a count of
 * removed rows on the response.
 */
@Serializable
data class CronDeleteAllFrame(
    override val v: Int = 1,
    override val type: String = "cron_delete_all",
    override val id: String,
    override val ts: String,
    @SerialName("request_id") val requestId: String,
    @SerialName("agent_id") val agentId: String,
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
        // Shim emits both fields independently (§2.2). `a2uiNegotiated`
        // is the boolean gate; `a2ui` carries the resolved version +
        // catalog and is non-null only when negotiation succeeded.
        @SerialName("a2ui_negotiated") val a2uiNegotiated: Boolean = false,
        @SerialName("a2ui") val a2ui: A2uiHandshakeAck? = null,
        @SerialName("canonical_live_transport") val canonicalLiveTransport: String? = null,
    ) : ServerFrame

    /**
     * A2UI payload frame (§2.2 `a2ui_frame`). Emitted between
     * assistant_message deltas when an `<a2ui-json>` block was
     * extracted from the stream. Payloads route to the surface
     * manager, not the LettaMessage timeline mapper.
     *
     * On parse/validation failure the shim emits `ok=false` with
     * `parse_error` / `validation_error` populated — the renderer
     * should skip the frame but keep the socket alive.
     */
    data class A2ui(
        override val v: Int = 1,
        val type: String = "a2ui_frame",
        override val id: String,
        override val ts: String,
        @SerialName("agent_id") val agentId: String? = null,
        @SerialName("conversation_id") val conversationId: String? = null,
        @SerialName("turn_id") val turnId: String? = null,
        @SerialName("run_id") val runId: String? = null,
        val seq: Long? = null,
        val otid: String? = null,
        val ok: Boolean = true,
        @SerialName("parse_error") val parseError: String? = null,
        @SerialName("validation_error") val validationError: String? = null,
        val messages: List<A2uiMessage>,
        val raw: JsonObject,
    ) : ServerFrame

    /**
     * Server-advertised A2UI capabilities (§2.2 `a2ui_capabilities`).
     * Sent immediately after `welcome` when negotiation succeeded;
     * useful for clamping the renderer registry to what the server
     * supports. Informational — don't block welcome handling on it.
     */
    @Serializable
    data class A2uiCapabilities(
        override val v: Int = 1,
        val type: String = "a2ui_capabilities",
        override val id: String,
        override val ts: String,
        val version: String,
        @SerialName("catalog_id") val catalogId: String,
        @SerialName("supported_catalogs") val supportedCatalogs: List<String> = emptyList(),
        @SerialName("supported_widgets") val supportedWidgets: List<String> = emptyList(),
    ) : ServerFrame

    /**
     * Server ack for client `user_action` (§2.2). `status` is opaque
     * for forward-compat (`accepted`, `rejected`, future `queued`).
     * `reason` is populated only when `status == "rejected"`.
     */
    @Serializable
    data class UserActionAck(
        override val v: Int = 1,
        val type: String = "user_action_ack",
        override val id: String,
        override val ts: String,
        @SerialName("action_id") val actionId: String,
        val status: String,
        val reason: String? = null,
    ) : ServerFrame

    /**
     * Server outcome for a client `user_action` (lcp-uo5.14). Unlike
     * [UserActionAck], this is UX-facing: it tells mobile whether the
     * action was matched to a tool approval, injected as chat input,
     * recorded for later, rejected, or failed. [frameId] correlates to
     * the outbound [UserActionFrame.id].
     */
    @Serializable
    data class UserActionOutcome(
        override val v: Int = 1,
        val type: String = "user_action_outcome",
        override val id: String,
        override val ts: String,
        @OptIn(ExperimentalSerializationApi::class)
        @JsonNames("frame_id")
        val frameId: String,
        val outcome: String,
        @OptIn(ExperimentalSerializationApi::class)
        @JsonNames("action_id")
        val actionId: String? = null,
        val reason: String? = null,
        val idempotent: Boolean = false,
        @SerialName("agent_id") val agentId: String? = null,
        @SerialName("conversation_id") val conversationId: String? = null,
        @SerialName("turn_id") val turnId: String? = null,
        @SerialName("run_id") val runId: String? = null,
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
        @SerialName("conversation_id") val conversationId: String? = null,
        @SerialName("turn_id") val turnId: String? = null,
        @SerialName("run_id") val runId: String? = null,
        @SerialName("after_seq") val afterSeq: Long? = null,
        @SerialName("oldest_seq") val oldestSeq: Long? = null,
        @SerialName("last_seq") val lastSeq: Long? = null,
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
        val seq: Long? = null,
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
        val seq: Long? = null,
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
        val seq: Long? = null,
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
        val seq: Long? = null,
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
        val seq: Long? = null,
        @SerialName("seq_id") val seqId: Int? = null,
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
        val seq: Long? = null,
        @SerialName("seq_id") val seqId: Int? = null,
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
        val seq: Long? = null,
    ) : ServerFrame

    @Serializable
    data class ToolReturnMessage(
        override val v: Int = 1,
        val type: String = "tool_return_message",
        override val id: String,
        override val ts: String = "",
        @SerialName("agent_id") val agentId: String = "",
        @SerialName("conversation_id") val conversationId: String = "",
        @SerialName("turn_id") val turnId: String? = null,
        @SerialName("run_id") val runId: String? = null,
        @SerialName("tool_call_id") val toolCallId: String,
        val status: String = "success",
        @SerialName("tool_return") val toolReturn: String? = null,
        val stdout: List<String>? = null,
        val stderr: List<String>? = null,
        val seq: Long? = null,
    ) : ServerFrame

    /**
     * letta-mobile-2rkdj — Spec §11/§3.4: a single replayed (or
     * live-tailed) entry from a Run's `frames.jsonl`, emitted in
     * response to a [com.letta.mobile.data.transport.SubscribeFrame].
     *
     * `seq` is the cursor value to persist; on next reconnect, pass
     * `cursor: seq` to resume after this frame.
     *
     * `frame` is the raw original BridgeFrame the host emitted at
     * write time — same `message_type` discriminator as live frames.
     * Callers unwrap and route it through the normal handler so
     * replayed and live frames take the same code path.
     */
    data class SubscribeFrameMessage(
        override val v: Int = 1,
        val type: String = "subscribe_frame",
        override val id: String,
        override val ts: String,
        @SerialName("run_id") val runId: String,
        val seq: Long,
        val frame: JsonObject,
    ) : ServerFrame

    /**
     * letta-mobile-2rkdj — Spec §11/§3.4: terminal envelope for a
     * subscription. Emitted once the Run reaches a terminal status
     * AND the live-tail has caught up. Client should drop any
     * persisted cursor for this run.
     */
    @Serializable
    data class SubscribeDone(
        override val v: Int = 1,
        val type: String = "subscribe_done",
        override val id: String,
        override val ts: String,
        @SerialName("run_id") val runId: String,
        @SerialName("last_seq") val lastSeq: Long,
        val status: String,
    ) : ServerFrame

    // ─── Cron server frames (letta-mobile-d52f.1) ───────────────────
    //
    // `request_id` echoes the client's outbound request so the repo
    // layer can route the response to the awaiting continuation. The
    // shim returns `null` for `request_id` when the inbound frame
    // didn't carry one; CronRepository handles that by treating the
    // response as broadcast-only.

    @Serializable
    data class CronListResponse(
        override val v: Int = 1,
        val type: String = "cron_list_response",
        override val id: String,
        override val ts: String,
        @SerialName("request_id") val requestId: String? = null,
        val success: Boolean,
        val tasks: List<CronTask> = emptyList(),
        val error: String? = null,
    ) : ServerFrame

    @Serializable
    data class CronAddResponse(
        override val v: Int = 1,
        val type: String = "cron_add_response",
        override val id: String,
        override val ts: String,
        @SerialName("request_id") val requestId: String? = null,
        val success: Boolean,
        val task: CronTask? = null,
        val error: String? = null,
        val warning: String? = null,
    ) : ServerFrame

    @Serializable
    data class CronGetResponse(
        override val v: Int = 1,
        val type: String = "cron_get_response",
        override val id: String,
        override val ts: String,
        @SerialName("request_id") val requestId: String? = null,
        val success: Boolean,
        val task: CronTask? = null,
        val error: String? = null,
    ) : ServerFrame

    @Serializable
    data class CronDeleteResponse(
        override val v: Int = 1,
        val type: String = "cron_delete_response",
        override val id: String,
        override val ts: String,
        @SerialName("request_id") val requestId: String? = null,
        val success: Boolean,
        val error: String? = null,
    ) : ServerFrame

    @Serializable
    data class CronDeleteAllResponse(
        override val v: Int = 1,
        val type: String = "cron_delete_all_response",
        override val id: String,
        override val ts: String,
        @SerialName("request_id") val requestId: String? = null,
        val success: Boolean,
        val count: Long = 0L,
        val error: String? = null,
    ) : ServerFrame

    /**
     * Push notification emitted whenever the shim's `crons.json` changes
     * (a task fired, an external write moved mtime, a peer client did a
     * mutation). Carries only the post-event active count — the
     * canonical state lives in `crons.json` and the client re-runs
     * `cron_list` on receipt. `reason` is informational only:
     *   `scheduler_write`, `external_write`, `client_mutation`,
     *   `scheduler_started`, `scheduler_stopped`. Treat the set as open.
     */
    @Serializable
    data class CronsUpdated(
        override val v: Int = 1,
        val type: String = "crons_updated",
        override val id: String,
        override val ts: String,
        val reason: String,
        @SerialName("tasks_active") val tasksActive: Long = 0L,
        val at: String,
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
            "a2ui_frame" -> A2uiFrameDeserializer
            "a2ui_capabilities" -> ServerFrame.A2uiCapabilities.serializer()
            "subscribe_frame" -> SubscribeFrameDeserializer
            "subscribe_done" -> ServerFrame.SubscribeDone.serializer()
            "user_action_ack" -> ServerFrame.UserActionAck.serializer()
            "user_action_outcome" -> ServerFrame.UserActionOutcome.serializer()
            "cron_list_response" -> ServerFrame.CronListResponse.serializer()
            "cron_add_response" -> ServerFrame.CronAddResponse.serializer()
            "cron_get_response" -> ServerFrame.CronGetResponse.serializer()
            "cron_delete_response" -> ServerFrame.CronDeleteResponse.serializer()
            "cron_delete_all_response" -> ServerFrame.CronDeleteAllResponse.serializer()
            "crons_updated" -> ServerFrame.CronsUpdated.serializer()
            else -> UnknownFrameDeserializer
        }
    }
}

private object A2uiFrameDeserializer : kotlinx.serialization.KSerializer<ServerFrame> {
    override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor =
        kotlinx.serialization.descriptors.buildClassSerialDescriptor("ServerFrame.A2ui")

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): ServerFrame {
        val jsonDecoder = decoder as? kotlinx.serialization.json.JsonDecoder
            ?: error("A2uiFrameDeserializer requires a JsonDecoder")
        val element = jsonDecoder.decodeJsonElement().jsonObject
        // Shim §2.2: payload lives under `a2ui` (single object or array
        // of v0.9 messages). On `ok=false` the field may be absent.
        val payload = element["a2ui"]
        val ok = element["ok"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
        val messages = payload?.let { decodeA2uiMessages(jsonDecoder.json, it) }.orEmpty()
        return ServerFrame.A2ui(
            v = element["v"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
            id = element["id"]?.jsonPrimitive?.content.orEmpty(),
            ts = element["ts"]?.jsonPrimitive?.content.orEmpty(),
            agentId = element["agent_id"]?.jsonPrimitive?.content,
            conversationId = element["conversation_id"]?.jsonPrimitive?.content,
            turnId = element["turn_id"]?.jsonPrimitive?.content,
            runId = element["run_id"]?.jsonPrimitive?.content,
            seq = element["seq"]?.jsonPrimitive?.content?.toLongOrNull(),
            otid = element["otid"]?.jsonPrimitive?.content,
            ok = ok,
            parseError = element["parse_error"]?.jsonPrimitive?.content,
            validationError = element["validation_error"]?.jsonPrimitive?.content,
            messages = messages,
            raw = element,
        )
    }

    override fun serialize(
        encoder: kotlinx.serialization.encoding.Encoder,
        value: ServerFrame,
    ): Unit = error("ServerFrame.A2ui is inbound-only; encoding it is never valid")
}

/**
 * letta-mobile-2rkdj: hand-written deserializer for the `subscribe_frame`
 * envelope (§11). The wrapper carries `run_id` + `seq` + an opaque `frame`
 * object whose shape is whatever BridgeFrame the shim recorded. We keep
 * the inner `frame` as a raw [JsonObject] so the caller can re-decode it
 * with [ServerFrameSerializer] and route it through the same handler as
 * a live frame — replayed and live frames must take the same code path.
 */
private object SubscribeFrameDeserializer : kotlinx.serialization.KSerializer<ServerFrame> {
    override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor =
        kotlinx.serialization.descriptors.buildClassSerialDescriptor("ServerFrame.SubscribeFrameMessage")

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): ServerFrame {
        val jsonDecoder = decoder as? kotlinx.serialization.json.JsonDecoder
            ?: error("SubscribeFrameDeserializer requires a JsonDecoder")
        val element = jsonDecoder.decodeJsonElement().jsonObject
        val frameField = element["frame"]
        val innerFrame = (frameField as? JsonObject)
            ?: error("subscribe_frame envelope missing object-shaped 'frame' field")
        return ServerFrame.SubscribeFrameMessage(
            v = element["v"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
            id = element["id"]?.jsonPrimitive?.content.orEmpty(),
            ts = element["ts"]?.jsonPrimitive?.content.orEmpty(),
            runId = element["run_id"]?.jsonPrimitive?.content
                ?: error("subscribe_frame envelope missing 'run_id'"),
            seq = element["seq"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: error("subscribe_frame envelope missing numeric 'seq'"),
            frame = innerFrame,
        )
    }

    override fun serialize(
        encoder: kotlinx.serialization.encoding.Encoder,
        value: ServerFrame,
    ): Unit = error("ServerFrame.SubscribeFrameMessage is inbound-only; encoding it is never valid")
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
