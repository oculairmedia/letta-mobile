package com.letta.mobile.data.messaging

import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationClass

/**
 * letta-mobile-bn008.3: routing decision for an inbound direct agent-to-agent
 * message. Pure + deterministic so it is fully headless-testable; the QUIC
 * listener and turn-trigger wire around it.
 *
 * Policy (from the 2026-07 inter-agent routing discussion):
 *  - Ping-pong guard: drop a message from our own (or a sibling) identity so an
 *    agent never loops on its own output.
 *  - Loop-safety: at-most-once per inbound (dedupe by msgId), and a per-sender cap.
 *  - Route to the target agent's MOST-RECENT-INTERACTIVE conversation; NEVER an
 *    AUTONOMOUS (heartbeat/goal/dispatch) conversation.
 *  - Create-if-none: no interactive conversation -> create one and deliver.
 *  - Queue-if-busy: the chosen interactive conversation is busy -> queue on it;
 *    NEVER reroute to a different conversation on busy.
 *  - Never hard-fail on a missing conversation.
 *  - letta-mobile-5m1qy: an explicit targetConversationId overrides the
 *    most-recent pick (Deliver/Queue per busy state); a target that does not
 *    exist is dropped with the distinct reason "target_conversation_not_found"
 *    (never created). All safety guards still run before explicit targeting.
 */
class IrohAgentMessageRouter(
    /** This receiver's own agentId — messages from it are our own echo. */
    private val ownAgentId: String,
    /** Additional sibling identities to treat as own (loop-safety). */
    private val siblingAgentIds: Set<String> = emptySet(),
    /** Max inbound messages accepted per sender before capping (loop-safety). */
    private val maxPerSender: Int = 64,
) {
    // Loop-safety state: seen msgIds (at-most-once) + per-sender counts.
    private val seenMsgIds = LinkedHashSet<String>()
    private val perSenderCount = mutableMapOf<String, Int>()

    /** A conversation with a "busy" flag (an active run in flight). */
    data class ConversationState(val conversation: Conversation, val busy: Boolean)

    sealed interface RoutingDecision {
        /** Deliver to an existing interactive conversation + trigger a turn now. */
        data class Deliver(val conversationId: String) : RoutingDecision
        /** No interactive conversation exists — create one, then deliver. */
        data object CreateAndDeliver : RoutingDecision
        /** The chosen interactive conversation is busy — queue on it (do not reroute). */
        data class Queue(val conversationId: String) : RoutingDecision
        /** Dropped for a policy reason (ping-pong / duplicate / cap). */
        data class Dropped(val reason: String) : RoutingDecision
    }

    /**
     * Decide where an inbound message goes. [candidates] are the target agent's
     * conversations with busy state (any order); most-recent is picked by
     * lastMessageAt/updatedAt among the INTERACTIVE ones. When
     * [targetConversationId] is non-null (5m1qy), the message routes to that
     * exact conversation instead of the most-recent-interactive pick.
     */
    fun route(
        fromAgentId: String,
        msgId: String,
        candidates: List<ConversationState>,
        targetConversationId: String? = null,
    ): RoutingDecision {
        // Ping-pong guard: never process our own / a sibling's message.
        if (fromAgentId == ownAgentId || fromAgentId in siblingAgentIds) {
            return RoutingDecision.Dropped("ping_pong_own_or_sibling")
        }
        // At-most-once per inbound (dedupe by msgId).
        if (!seenMsgIds.add(msgId)) {
            return RoutingDecision.Dropped("duplicate_msg_id")
        }
        // Per-sender loop-safety cap.
        val count = (perSenderCount[fromAgentId] ?: 0) + 1
        perSenderCount[fromAgentId] = count
        if (count > maxPerSender) {
            return RoutingDecision.Dropped("per_sender_cap_exceeded")
        }

        // letta-mobile-5m1qy: an explicit target conversation from the sender
        // overrides the most-recent-interactive pick — but only AFTER the safety
        // guards above, and it must NEVER fall through to CreateAndDeliver
        // (a missing target is dropped with a distinct reason, not created).
        if (targetConversationId != null) {
            val target = candidates.find { it.conversation.id.value == targetConversationId }
                ?: return RoutingDecision.Dropped("target_conversation_not_found")
            return if (target.busy) {
                RoutingDecision.Queue(targetConversationId)
            } else {
                RoutingDecision.Deliver(targetConversationId)
            }
        }

        // Most-recent INTERACTIVE conversation (never AUTONOMOUS/heartbeat).
        val interactive = candidates
            .filter { it.conversation.effectiveClass == ConversationClass.INTERACTIVE }
            .maxByOrNull { recencyKey(it.conversation) }
            ?: return RoutingDecision.CreateAndDeliver

        return if (interactive.busy) {
            RoutingDecision.Queue(interactive.conversation.id.value)
        } else {
            RoutingDecision.Deliver(interactive.conversation.id.value)
        }
    }

    private fun recencyKey(c: Conversation): String =
        c.lastMessageAt ?: c.updatedAt ?: c.createdAt ?: ""
}
