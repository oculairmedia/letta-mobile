package com.letta.mobile.data.messaging

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentIdNamespace
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageId
import kotlinx.serialization.Serializable

/** Routes inbound messages by conversation lane, after loop-safety checks. */
class IrohAgentMessageRouter(
    /** This receiver's own agentId — messages from it are our own echo. */
    private val ownAgentId: String,
    /** Additional sibling identities to treat as own (loop-safety). */
    private val siblingAgentIds: Set<String> = emptySet(),
    /** Max inbound messages accepted per sender before capping (loop-safety). */
    private val maxPerSender: Int = 64,
    private val maxReservations: Int = 4096,
    private val maxSenders: Int = 1024,
) {
    /** Retained entries are never evicted: capacity denies new work instead of allowing replay. */
    @Serializable
    enum class ReservationState { RESERVED, ACCEPTED, AMBIGUOUS, PERSISTED }

    @Serializable
    data class Snapshot(
        val version: Int,
        val reservations: Map<String, Map<String, ReservationState>>,
        val senderAttempts: Map<String, Int>,
    )

    private val loopIdentities = (siblingAgentIds + ownAgentId).map(AgentIdNamespace::normalizeToBareId).toSet()
    private val reservations = linkedMapOf<Pair<String, String>, ReservationState>()
    private val perSenderCount = mutableMapOf<String, Int>()

    /** Called under the receiver's routing lock, before any application dispatch. */
    fun snapshot(): Snapshot = Snapshot(
        version = 2,
        reservations = reservations.entries.groupBy { it.key.first }.mapValues { (_, entries) ->
            entries.associate { it.key.second to it.value }
        },
        senderAttempts = perSenderCount.toMap(),
    )

    /** Strict journal recovery; unfinished reservations remain suppressive after a restart. */
    fun restore(snapshot: Snapshot) {
        require(snapshot.version == 2) { "Senderless A2A journals require external reconciliation" }
        require(snapshot.reservations.values.sumOf { it.size.toLong() } <= maxReservations && snapshot.senderAttempts.size <= maxSenders)
        require(snapshot.reservations.all { (sender, messages) ->
            validIdentity(sender) && sender in snapshot.senderAttempts && messages.isNotEmpty() && messages.keys.all(::validIdentity)
        })
        require(snapshot.senderAttempts.all { (sender, count) -> validIdentity(sender) && count in 0..maxPerSender })
        reservations.clear()
        reservations.putAll(snapshot.reservations.flatMap { (sender, messages) ->
            messages.map { (message, state) ->
                (sender to message) to if (state == ReservationState.RESERVED) ReservationState.AMBIGUOUS else state
            }
        })
        perSenderCount.clear()
        perSenderCount.putAll(snapshot.senderAttempts)
    }

    /** Null releases only a definitely unexecuted attempt; every other state blocks re-execution. */
    fun settle(sender: AgentId, message: MessageId, state: ReservationState?) {
        val key = reservationKey(sender, message)
        if (key !in reservations || reservations[key] == ReservationState.PERSISTED) return
        if (state == null) reservations.remove(key) else reservations[key] = state
    }

    private fun reservationKey(sender: AgentId, message: MessageId): Pair<String, String> =
        AgentIdNamespace.normalizeToBareId(sender.value) to message.value

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
        guard(AgentId(fromAgentId), MessageId(msgId))?.let { return it }
        val decision = when (val target = address.targetConversationId) {
            null -> routeLane(address, candidates)
            else -> routeExplicitTarget(ConversationId(target), candidates)
        }
        if (decision is RoutingDecision.Dropped) reservations.remove(reservationKey(AgentId(fromAgentId), MessageId(msgId)))
        return decision
    }

    private fun guard(sender: AgentId, message: MessageId): RoutingDecision.Dropped? {
        val fromAgentId = AgentIdNamespace.normalizeToBareId(sender.value)
        val msgId = message.value
        val count = perSenderCount[fromAgentId] ?: 0
        val atCapacity = reservations.size >= maxReservations ||
            (fromAgentId !in perSenderCount && perSenderCount.size >= maxSenders)
        return when {
            fromAgentId in loopIdentities -> RoutingDecision.Dropped("ping_pong_own_or_sibling")
            !validIdentity(fromAgentId) || !validIdentity(msgId) -> RoutingDecision.Dropped("invalid_identity")
            reservationKey(sender, message) in reservations -> RoutingDecision.Dropped("duplicate_msg_id")
            atCapacity -> RoutingDecision.Dropped("reservation_capacity_exceeded")
            count >= maxPerSender -> RoutingDecision.Dropped("per_sender_cap_exceeded")
            else -> {
                perSenderCount[fromAgentId] = count + 1
                reservations[reservationKey(sender, message)] = ReservationState.RESERVED
                null
            }
        }
    }

    private fun validIdentity(value: String): Boolean = value.isNotBlank() && value.length <= MAX_IDENTITY_CHARS

    private fun routeLane(address: InboundAddress, candidates: List<ConversationState>): RoutingDecision =
        when (val lane = address.lane ?: "inbox") {
            "home" -> RoutingDecision.Dropped("home_lane_forbidden")
            "inbox" -> {
                val matches = laneMatches(candidates.map { it.conversation }, lane)
                when (matches.size) {
                    0 -> RoutingDecision.CreateAndDeliver
                    1 -> {
                        val inbox = matches.single()
                        routeExplicitTarget(inbox.id, candidates)
                    }
                    else -> RoutingDecision.Dropped("duplicate_lane_tags")
                }
            }
            else -> RoutingDecision.Dropped("unsupported_lane")
        }

    companion object {
        private const val MAX_IDENTITY_CHARS = 512

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
        requested: ConversationId,
        candidates: List<ConversationState>,
    ): RoutingDecision {
        val target = candidates.find { it.conversation.id == requested }
            ?: return RoutingDecision.Dropped("target_conversation_not_found")
        // Lanes v1: an explicit id is not a back door around the lane rules. Work
        // conversations are records, never inbound targets, and home is owner-only.
        if ("lane:work" in target.conversation.tags) return RoutingDecision.Dropped("target_is_work_lane")
        if ("lane:home" in target.conversation.tags) return RoutingDecision.Dropped("home_lane_forbidden")
        return if (target.busy) {
            RoutingDecision.Queue(requested.value)
        } else {
            RoutingDecision.Deliver(requested.value)
        }
    }

}
