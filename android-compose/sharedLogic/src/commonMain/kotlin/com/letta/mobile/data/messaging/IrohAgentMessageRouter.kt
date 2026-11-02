package com.letta.mobile.data.messaging

import com.letta.mobile.data.model.Conversation

/** Routes inbound messages by conversation lane, after loop-safety checks. */
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

    /** Optional explicit conversation and lane for an inbound message. */
    data class InboundAddress(val targetConversationId: String? = null, val lane: String? = null)

    sealed interface RoutingDecision {
        /** Deliver to an existing conversation + trigger a turn now. */
        data class Deliver(val conversationId: String) : RoutingDecision
        /** No inbox exists — create one, then deliver. */
        data object CreateAndDeliver : RoutingDecision
        /** The chosen conversation is busy — queue on it (do not reroute). */
        data class Queue(val conversationId: String) : RoutingDecision
        /** Dropped for a policy reason, including loop-safety or an invalid lane. */
        data class Dropped(val reason: String) : RoutingDecision
    }

    /**
     * Decide where an inbound message goes. [candidates] are the target agent's
     * conversations with busy state (any order). The explicit conversation in
     * [address] takes precedence; otherwise its lane defaults to inbox.
     */
    fun route(
        fromAgentId: String,
        msgId: String,
        candidates: List<ConversationState>,
        address: InboundAddress = InboundAddress(),
    ): RoutingDecision {
        guard(fromAgentId, msgId)?.let { return it }
        if (address.targetConversationId != null) return routeExplicitTarget(address.targetConversationId, candidates)
        return routeLane(address.lane ?: "inbox", candidates)
    }

    private fun guard(fromAgentId: String, msgId: String): RoutingDecision.Dropped? {
        if (fromAgentId == ownAgentId || fromAgentId in siblingAgentIds) {
            return RoutingDecision.Dropped("ping_pong_own_or_sibling")
        }
        if (!seenMsgIds.add(msgId)) return RoutingDecision.Dropped("duplicate_msg_id")
        val count = (perSenderCount[fromAgentId] ?: 0) + 1
        perSenderCount[fromAgentId] = count
        return if (count > maxPerSender) RoutingDecision.Dropped("per_sender_cap_exceeded") else null
    }

    private fun routeLane(lane: String, candidates: List<ConversationState>): RoutingDecision = when (lane) {
        "home" -> RoutingDecision.Dropped("home_lane_forbidden")
        "inbox" -> {
            val matches = laneMatches(candidates.map { it.conversation }, lane)
            when (matches.size) {
                0 -> RoutingDecision.CreateAndDeliver
                1 -> {
                    val inbox = matches.single()
                    routeExplicitTarget(inbox.id.value, candidates)
                }
                else -> RoutingDecision.Dropped("duplicate_lane_tags")
            }
        }
        else -> RoutingDecision.Dropped("unsupported_lane")
    }

    companion object {
        /** Select the conversation carrying the exact lane tag. */
        fun pickLane(conversations: List<Conversation>, lane: String): Conversation? =
            laneMatches(conversations, lane).singleOrNull()

        private fun laneMatches(conversations: List<Conversation>, lane: String): List<Conversation> =
            conversations.filter { "lane:$lane" in it.tags }
    }

    /**
     * Route to the sender-declared target conversation.
     * A target missing from [candidates] is dropped with a distinct reason —
     * never created — so an explicit send cannot silently open a fresh conv.
     */
    private fun routeExplicitTarget(
        targetConversationId: String,
        candidates: List<ConversationState>,
    ): RoutingDecision {
        val target = candidates.find { it.conversation.id.value == targetConversationId }
            ?: return RoutingDecision.Dropped("target_conversation_not_found")
        // Lanes v1: an explicit id is not a back door around the lane rules. Work
        // conversations are records, never inbound targets, and home is owner-only.
        if ("lane:work" in target.conversation.tags) return RoutingDecision.Dropped("target_is_work_lane")
        if ("lane:home" in target.conversation.tags) return RoutingDecision.Dropped("home_lane_forbidden")
        return if (target.busy) {
            RoutingDecision.Queue(targetConversationId)
        } else {
            RoutingDecision.Deliver(targetConversationId)
        }
    }

}
