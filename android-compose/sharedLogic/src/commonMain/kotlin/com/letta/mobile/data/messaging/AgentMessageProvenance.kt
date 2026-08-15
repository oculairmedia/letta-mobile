package com.letta.mobile.data.messaging

import androidx.compose.runtime.Immutable

/**
 * letta-mobile-slqfp: structured, transport-carried provenance for an
 * inter-agent (a2a) message projected onto the chat render layer.
 *
 * This model is built ONLY from explicit structural signals — the a2a wire
 * envelope (via [AgentMessageClientId]) or the `agent_message_send` tool
 * call's own arguments/result (via [AgentMessageProvenanceProjection]) —
 * NEVER by pattern-matching message body/content text. If neither structural
 * signal is present, no [AgentMessageProvenance] is produced and the message
 * renders as ordinary chat. See letta-mobile-i9h61 / letta-mobile-bn008 for
 * the transport this projects.
 */
@Immutable
data class AgentMessageProvenance(
    /** Whether this conversation's agent is the sender or the recipient. */
    val direction: AgentMessageDirection,
    /** Stable agentId of the sender (never a display name). */
    val fromAgentId: String,
    /** Stable agentId of the recipient (never a display name). */
    val toAgentId: String,
    /** Best-effort resolved display name for [fromAgentId]; null = unresolved. */
    val fromAgentName: String? = null,
    /** Best-effort resolved display name for [toAgentId]; null = unresolved. */
    val toAgentName: String? = null,
    /** The a2a wire message id (`IrohAgentMessage.msgId`). */
    val msgId: String,
    /** Truthful delivery state — never a guess. */
    val deliveryState: AgentMessageDeliveryState,
    /** Typed failure reason when [deliveryState] is [AgentMessageDeliveryState.FAILED]. */
    val failureReason: String? = null,
    /**
     * The conversation id this message was routed onto by
     * [com.letta.mobile.data.messaging.IrohAgentMessageRouter] (when known —
     * only observable from the sending side's own routing decision or the
     * receiving side's explicit-target field).
     */
    val routingConversationId: String? = null,
    /** Transport identifier, always "iroh" today. Kept for forward-compat. */
    val transport: String = "iroh",
    /** Wall-clock send time, when known. */
    val sentAtEpochMs: Long? = null,
    /** Wall-clock ack/confirmation time, when known. */
    val ackAtEpochMs: Long? = null,
) {
    /** Best-effort round-trip latency; null unless both timestamps are known. */
    val ackLatencyMs: Long?
        get() = if (sentAtEpochMs != null && ackAtEpochMs != null) {
            (ackAtEpochMs - sentAtEpochMs).coerceAtLeast(0)
        } else {
            null
        }

    /** Message kind — today only a direct agent-to-agent chat message. */
    val kind: AgentMessageKind get() = AgentMessageKind.DIRECT_MESSAGE
}

@Immutable
enum class AgentMessageDirection { INBOUND, OUTBOUND }

/**
 * Truthful delivery state surfaced to the user. Distinct from
 * [com.letta.mobile.data.timeline.DeliveryState] (which tracks the LOCAL
 * optimistic-send lifecycle of any chat message) — this tracks the a2a
 * APPLICATION delivery lifecycle end to end: local transport acceptance is
 * not receiver application confirmation (letta-mobile-i9h61.2).
 */
@Immutable
enum class AgentMessageDeliveryState { PENDING, SENT, RECEIVER_CONFIRMED, FAILED }

@Immutable
enum class AgentMessageKind { DIRECT_MESSAGE }
