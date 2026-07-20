package com.letta.mobile.data.transport

import com.letta.mobile.data.a2ui.A2uiHandshakeAck
import com.letta.mobile.data.a2ui.A2uiMessage
import com.letta.mobile.data.model.CronTask
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentTodo
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject

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
        @SerialName("request_id") val requestId: String? = null,
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
        override val id: String = "",
        override val ts: String = "",
        @SerialName("turn_id") val turnId: String? = null,
        @SerialName("run_id") val runId: String? = null,
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
        override val id: String = "",
        override val ts: String = "",
        @SerialName("turn_id") val turnId: String? = null,
        @SerialName("run_id") val runId: String? = null,
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
    data class UserMessage(
        override val v: Int = 1,
        val type: String = "user_message",
        override val id: String,
        override val ts: String = "",
        @SerialName("agent_id") val agentId: String? = null,
        @SerialName("conversation_id") val conversationId: String? = null,
        @SerialName("turn_id") val turnId: String? = null,
        @SerialName("run_id") val runId: String? = null,
        val content: String,
        val otid: String? = null,
        val seq: Long? = null,
        @SerialName("seq_id") val seqId: Int? = null,
    ) : ServerFrame

    @Serializable
    data class AssistantMessage(
        override val v: Int = 1,
        val type: String = "assistant_message",
        override val id: String,
        override val ts: String = "",
        @SerialName("agent_id") val agentId: String? = null,
        @SerialName("conversation_id") val conversationId: String? = null,
        @SerialName("turn_id") val turnId: String? = null,
        @SerialName("run_id") val runId: String? = null,
        val content: String,
        val otid: String? = null,
        val seq: Long? = null,
        @SerialName("seq_id") val seqId: Int? = null,
        @SerialName("stream_mode") val streamMode: String? = null,
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
        @SerialName("stream_mode") val streamMode: String? = null,
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
        @SerialName("tool_return") val toolReturn: JsonElement? = null,
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

    @Serializable
    data class GoalsUpdated(
        override val v: Int = 1,
        val type: String = "goals_updated",
        override val id: String,
        override val ts: String,
        val reason: String = "",
        val at: String = "",
    ) : ServerFrame

    @Serializable
    data class AgentUpdated(
        override val v: Int = 1,
        val type: String = "agent_updated",
        override val id: String,
        override val ts: String,
        @SerialName("agent_id") val agentId: String,
        val reason: String = "",
        val at: String = "",
    ) : ServerFrame

    // ─── Subagent server frames (letta-mobile-73o2h.3) ──────────────
    //
    // `request_id` echoes the client's outbound request so the repo
    // layer can route the response to the awaiting continuation. The
    // shim returns `null` when the inbound frame didn't carry one;
    // SubagentRepository treats that as broadcast-only.

    /**
     * Response to [SubagentListFrame] (§13.2). `subagents` carries the
     * full enumeration (active-only unless the request set `all`).
     */
    @Serializable
    data class SubagentListResponse(
        override val v: Int = 1,
        val type: String = "subagent_list_response",
        override val id: String,
        override val ts: String,
        @SerialName("request_id") val requestId: String? = null,
        val success: Boolean,
        val subagents: List<SubagentEntry> = emptyList(),
        val error: String? = null,
    ) : ServerFrame

    /**
     * Response to [SubagentTodosFrame] (§13.3). `subagent` is the matched
     * registry entry; `todos` is the latest TodoWrite snapshot. `found`
     * / `todosFound` degrade gracefully when the subagent or its todos
     * could not be resolved.
     */
    @Serializable
    data class SubagentTodosResponse(
        override val v: Int = 1,
        val type: String = "subagent_todos_response",
        override val id: String,
        override val ts: String,
        @SerialName("request_id") val requestId: String? = null,
        val success: Boolean,
        val found: Boolean = false,
        val subagent: SubagentEntry? = null,
        val todos: List<SubagentTodo> = emptyList(),
        @SerialName("todos_found") val todosFound: Boolean = false,
        val error: String? = null,
    ) : ServerFrame

    /**
     * Push notification emitted per-socket (after `hello`, mirroring
     * [CronsUpdated]) whenever a subagent starts or reaches a terminal
     * state (§13.4). Carries the changed [subagent], a fresh
     * [subagentsActive] snapshot so the bar reduces by replacement, and
     * an informational [reason] (e.g. `started`, `completed`, `failed`).
     */
    @Serializable
    data class SubagentsUpdated(
        override val v: Int = 1,
        val type: String = "subagents_updated",
        override val id: String,
        override val ts: String,
        val reason: String = "",
        val subagent: SubagentEntry? = null,
        @SerialName("subagents_active") val subagentsActive: List<SubagentEntry> = emptyList(),
        val at: String = "",
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
