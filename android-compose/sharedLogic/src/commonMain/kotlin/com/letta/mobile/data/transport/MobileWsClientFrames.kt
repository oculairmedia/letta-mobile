package com.letta.mobile.data.transport

import com.letta.mobile.data.a2ui.A2UI_DEFAULT_SUPPORTED_CATALOGS
import com.letta.mobile.data.a2ui.A2UI_DEFAULT_SUPPORTED_WIDGETS
import com.letta.mobile.data.a2ui.A2UI_HELLO_VERSION
import com.letta.mobile.data.a2ui.A2uiThemeHints
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

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

// ─── Subagent client frames (letta-mobile-73o2h.3, sister to cron) ───
//
// The active-subagent registry mirrors the cron request/response +
// server-push shape (MOBILE_WS_PROTOCOL.md §13). Each request frame
// carries a `request_id` independent of the envelope `id`; the shim
// echoes it on the matching response so the repo layer routes the
// awaited continuation to its caller instead of broadcasting.

/**
 * Enumerate registered subagents (§13.2). Active-only by default; pass
 * `all = true` to include terminal (completed/failed) entries. The
 * registry is per-socket, so there is no agent/conversation filter.
 */
@Serializable
data class SubagentListFrame(
    override val v: Int = 1,
    override val type: String = "subagent_list",
    override val id: String,
    override val ts: String,
    @SerialName("request_id") val requestId: String,
    val all: Boolean = false,
) : ClientFrame

/**
 * Fetch one subagent's latest TodoWrite snapshot + lifecycle (§13.3),
 * keyed by the parent Agent `tool_call_id`.
 */
@Serializable
data class SubagentTodosFrame(
    override val v: Int = 1,
    override val type: String = "subagent_todos",
    override val id: String,
    override val ts: String,
    @SerialName("request_id") val requestId: String,
    @SerialName("tool_call_id") val toolCallId: String,
) : ClientFrame
