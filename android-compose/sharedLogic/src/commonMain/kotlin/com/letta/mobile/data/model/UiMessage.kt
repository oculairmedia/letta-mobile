package com.letta.mobile.data.model

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.messaging.AgentMessageProvenance

@Immutable
data class UiMessage(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: String,
    /**
     * Server run id for assistant-side messages that belong to a run.
     * Null for user messages, which trigger runs rather than belonging to one,
     * and for older hydrated history that predates run tracking.
     */
    val runId: String? = null,
    // letta-mobile-c4igq.4: the owning agent of this message. Used to scope chat
    // render strictly by (agentId, conversationId) so a foreign-agent run block
    // can never leak into another agent's conversation. Null = legacy/unknown
    // (never dropped, to avoid false-dropping same-agent history).
    val agentId: String? = null,
    /**
     * Server step id for assistant-side messages that belong to a run step.
     * Null for user messages and older history that predates step tracking.
     */
    val stepId: String? = null,
    /**
     * Client transaction / otid that survives Pending → Confirmed so LazyColumn
     * item keys stay stable across optimistic local rows and server ack.
     * Null when the event has no client-scoped identity (pure hydrated history).
     */
    val clientMessageId: String? = null,
    val isPending: Boolean = false,
    val isReasoning: Boolean = false,
    /**
     * Server-emitted error frame (run aborted, tool failed, rate-limit,
     * etc.). Renderers paint these with a destructive accent so the user
     * sees that something went wrong instead of a silent dropped spinner.
     * letta-mobile-5s1n.
     */
    val isError: Boolean = false,
    /**
     * letta-mobile-jt4wq: this LOCAL message could not be handed to the
     * transport (delivery state FAILED). It is NOT [isError].
     *
     * These were previously folded together, which meant a user's own prompt
     * was re-rendered as a left-aligned destructive "Error" bubble containing
     * their own words — the message looked like a server failure report rather
     * than something they said. The two states need different treatment: a
     * send failure is local, retryable, and belongs to the user's own bubble;
     * an error frame is the server telling us the run went wrong.
     */
    val isSendFailed: Boolean = false,
    /**
     * Best-effort elapsed time from the triggering user prompt to this
     * assistant-side message. Populated at render time when server timestamps
     * are available; null means latency should be hidden.
     */
    val latencyMs: Long? = null,
    val toolCalls: List<UiToolCall>? = null,
    val generatedUi: UiGeneratedComponent? = null,
    val approvalRequest: UiApprovalRequest? = null,
    val approvalResponse: UiApprovalResponse? = null,
    val subagentNotification: UiSubagentNotification? = null,
    /**
     * Image attachments rendered as thumbnails in the bubble. Populated for
     * outgoing user messages that carried attachments through the Timeline
     * send path, and — since letta-mobile-mge5.24 — for USER/ASSISTANT
     * messages hydrated from server history whose `content` is a multimodal
     * JSON array containing inline image parts.
     */
    val attachments: List<UiImageAttachment> = emptyList(),
    /**
     * letta-mobile-slqfp: structured inter-agent (a2a) provenance for this
     * message, when it is a message this conversation's agent RECEIVED from
     * another agent (INBOUND). Never inferred from [content] — see
     * [com.letta.mobile.data.messaging.AgentMessageProvenanceProjection].
     * OUTBOUND provenance (this agent sending to another) lives on the
     * `agent_message_send` [UiToolCall.agentMessageProvenance] instead, since
     * the send is represented as a tool call, not a standalone message.
     */
    val agentMessageProvenance: AgentMessageProvenance? = null,
)

@Immutable
data class UiImageAttachment(
    val base64: String,
    val mediaType: String,
)

@Immutable
data class UiToolCall(
    val name: String,
    val arguments: String,
    val result: String?,
    /** Optional compact target shown beside [name] without replacing expanded arguments. */
    val displayTarget: String? = null,
    val status: String? = null,
    val generatedImageAttachments: List<UiImageAttachment> = emptyList(),
    /**
     * Best-effort wall-clock execution duration for the tool call, measured
     * from the tool-call message timestamp to the matching tool-return
     * timestamp when both are available.
     */
    val executionTimeMs: Long? = null,
    val toolCallId: String? = null,
    /**
     * Folded-in approval outcome for this specific tool call, when the mapper
     * absorbed a bare `approve=true` / `approve=false` `APPROVAL_RESPONSE`
     * into the owning tool-call bubble instead of emitting a standalone
     * "Approved" / "Rejected" card (letta-mobile-23h5).
     *
     * `null` means no decision is attached — either because the call didn't
     * need approval, because the response carried a reason (in which case the
     * standalone card is retained so the note is visible), or because the
     * decision hasn't arrived yet.
     */
    val approvalDecision: UiToolApprovalDecision? = null,
    val subagentDispatch: UiSubagentDispatch? = null,
    /**
     * letta-mobile-fe51r (P2b pointer diet): non-null when [result] is a
     * server-projected preview of a larger tool-return body. Carries the
     * tool-return message id needed to lazily fetch the full output when the
     * user expands the card.
     */
    val resultTruncation: UiToolResultTruncation? = null,
    /**
     * letta-mobile-slqfp: structured OUTBOUND inter-agent provenance when
     * this tool call is `agent_message_send` — see
     * [com.letta.mobile.data.messaging.AgentMessageProvenanceProjection.projectOutbound].
     * Null for every other tool call.
     */
    val agentMessageProvenance: AgentMessageProvenance? = null,
)

/**
 * Marker that a tool-call's result is a truncated preview; the full body can
 * be fetched on demand via `tool_return.get` using [messageId].
 * letta-mobile-fe51r.
 */
@Immutable
data class UiToolResultTruncation(
    val messageId: String,
    val byteLen: Long,
)

@Immutable
data class UiSubagentDispatch(
    val toolCallId: String?,
    val description: String,
    val subagentType: String,
    val runInBackground: Boolean,
    val prompt: String,
    val taskId: String? = null,
    val subagentAgentId: String? = null,
)

@Immutable
data class UiSubagentNotification(
    val status: String,
    val summary: String?,
    val result: String?,
    val usage: String?,
    val transcriptUri: String?,
    val taskId: String? = null,
    val subagentAgentId: String? = null,
    val toolCallId: String? = null,
    val durationMs: Long? = null,
)

/**
 * Compact, inline representation of an approval decision for display on the
 * tool-call card header (see `ChatMessageComponents.ToolCallCard`).
 */
@Immutable
enum class UiToolApprovalDecision {
    Approved,
    Rejected,
}

@Immutable
data class UiGeneratedComponent(
    val name: String,
    val propsJson: String,
    val fallbackText: String? = null,
)

@Immutable
data class UiApprovalRequest(
    val requestId: String,
    val toolCalls: List<UiApprovalToolCall>,
)

@Immutable
data class UiApprovalToolCall(
    val toolCallId: String,
    val name: String,
    val arguments: String,
)

@Immutable
data class UiApprovalResponse(
    val requestId: String? = null,
    val approved: Boolean? = null,
    val reason: String? = null,
    val approvals: List<UiApprovalDecision> = emptyList(),
)

@Immutable
data class UiApprovalDecision(
    val toolCallId: String,
    val approved: Boolean? = null,
    val status: String? = null,
    val reason: String? = null,
)
