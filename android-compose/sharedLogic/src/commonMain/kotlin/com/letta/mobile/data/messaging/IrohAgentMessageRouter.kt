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
    /** Max routing admissions per sender within [senderWindowMillis] (loop-safety). */
    private val maxPerSender: Int = 64,
    private val maxReservations: Int = 4096,
    private val maxSenders: Int = 1024,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val senderWindowMillis: Long = DEFAULT_SENDER_WINDOW_MILLIS,
) {
    /** Reservations suppress replay for fourteen days, including unfinished application dispatch. */
    @Serializable
    enum class ReservationState { RESERVED, ACCEPTED, AMBIGUOUS, PERSISTED }

    @Serializable
    data class Snapshot(
        val version: Int,
        val reservations: Map<String, Map<String, ReservationState>>,
        /** Version 2 migration input only; version 3 never writes lifetime totals. */
        val senderAttempts: Map<String, Int>,
        val reservationTimes: Map<String, Map<String, Long>>? = null,
        val senderAcceptedAt: Map<String, List<Long>>? = null,
    )

    private val loopIdentities = (siblingAgentIds + ownAgentId).map(AgentIdNamespace::normalizeToBareId).toSet()
    private val reservations = linkedMapOf<Pair<String, String>, ReservationState>()
    private val reservationTimes = linkedMapOf<Pair<String, String>, Long>()
    private val senderAcceptedAt = mutableMapOf<String, List<Long>>()

    init {
        require(senderWindowMillis > 0 && senderWindowMillis <= RESERVATION_RETENTION_MILLIS)
    }

    /** Called under the receiver's routing lock, before any application dispatch. */
    fun snapshot(): Snapshot = Snapshot(
        version = 3,
        senderAttempts = emptyMap(),
        reservations = reservations.entries.groupBy { it.key.first }.mapValues { (_, entries) ->
            entries.associate { it.key.second to it.value }
        },
        reservationTimes = reservationTimes.entries.groupBy { it.key.first }.mapValues { (_, entries) ->
            entries.associate { it.key.second to it.value }
        },
        senderAcceptedAt = senderAcceptedAt.toMap(),
    )

    /** Strict journal recovery; unfinished reservations remain suppressive after a restart. */
    fun restore(snapshot: Snapshot) {
        require(snapshot.version in 2..3) { "Senderless A2A journals require external reconciliation" }
        require(snapshot.reservations.values.sumOf { it.size.toLong() } <= maxReservations)
        require(snapshot.reservations.size <= maxSenders)
        require(snapshot.reservations.all { (sender, messages) ->
            validIdentity(sender) && messages.keys.all(::validIdentity)
        })
        require(snapshot.reservations.values.all { it.isNotEmpty() })
        val times = restoredTimes(snapshot)
        reservations.clear()
        reservations.putAll(snapshot.reservations.flatMap { (sender, messages) ->
            messages.map { (message, state) ->
                (sender to message) to if (state == ReservationState.RESERVED) ReservationState.AMBIGUOUS else state
            }
        })
        reservationTimes.clear()
        reservationTimes.putAll(times.flatMap { (sender, messages) -> messages.map { (message, time) -> (sender to message) to time } })
        senderAcceptedAt.clear()
        if (snapshot.version == 3) senderAcceptedAt.putAll(requireNotNull(snapshot.senderAcceptedAt))
    }

    private fun restoredTimes(snapshot: Snapshot): Map<String, Map<String, Long>> = when (snapshot.version) {
        2 -> migrateLegacyTimes(snapshot)
        else -> {
            val times = requireNotNull(snapshot.reservationTimes)
            val admissions = requireNotNull(snapshot.senderAcceptedAt)
            require(times.keys == snapshot.reservations.keys)
            require(snapshot.reservations.all { (sender, messages) -> times[sender]?.keys == messages.keys })
            require(times.values.flatMap { it.values }.all { it >= 0 })
            require((admissions.keys + snapshot.reservations.keys).size <= maxSenders)
            require(admissions.all { (sender, accepted) -> validIdentity(sender) && accepted.size <= maxPerSender })
            require(admissions.values.flatten().all { it >= 0 })
            times
        }
    }

    private fun migrateLegacyTimes(snapshot: Snapshot): Map<String, Map<String, Long>> {
        require(snapshot.senderAttempts.size <= maxSenders)
        require(snapshot.senderAttempts.all { (sender, count) -> validIdentity(sender) && count in 0..maxPerSender })
        require(snapshot.reservations.keys.all { it in snapshot.senderAttempts })
        val now = clock()
        return snapshot.reservations.mapValues { (_, messages) -> messages.mapValues { now } }
    }

    /** Null releases only a definitely unexecuted attempt; every other state blocks re-execution. */
    fun settle(sender: AgentId, message: MessageId, state: ReservationState?) {
        val key = reservationKey(sender, message)
        if (key !in reservations || reservations[key] == ReservationState.PERSISTED) return
        if (state == null) {
            reservations.remove(key)
            reservationTimes.remove(key)
        } else reservations[key] = state
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
        /** Suppress dispatch for a policy reason or an already reserved message. */
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
        val now = clock()
        prune(now)
        guard(AgentId(fromAgentId), MessageId(msgId))?.let { return it }
        val decision = when (val target = address.targetConversationId) {
            null -> routeLane(address, candidates)
            else -> routeExplicitTarget(ConversationId(target), candidates)
        }
        val key = reservationKey(AgentId(fromAgentId), MessageId(msgId))
        if (decision is RoutingDecision.Dropped) reservations.remove(key)
        else {
            reservationTimes[key] = now
            senderAcceptedAt[key.first] = senderAcceptedAt[key.first].orEmpty() + now
        }
        return decision
    }

    private fun guard(sender: AgentId, message: MessageId): RoutingDecision.Dropped? {
        val fromAgentId = AgentIdNamespace.normalizeToBareId(sender.value)
        val msgId = message.value
        val count = senderAcceptedAt[fromAgentId]?.size ?: 0
        val senders = senderAcceptedAt.keys + reservations.keys.map { it.first }
        val senderAtCapacity = fromAgentId !in senders && senders.size >= maxSenders
        val atCapacity = reservations.size >= maxReservations || senderAtCapacity
        return when {
            fromAgentId in loopIdentities -> RoutingDecision.Dropped("ping_pong_own_or_sibling")
            !validIdentity(fromAgentId) || !validIdentity(msgId) -> RoutingDecision.Dropped("invalid_identity")
            reservations[reservationKey(sender, message)] == ReservationState.PERSISTED -> RoutingDecision.Dropped("duplicate_persisted")
            reservationKey(sender, message) in reservations -> RoutingDecision.Dropped("duplicate_msg_id")
            atCapacity -> RoutingDecision.Dropped("reservation_capacity_exceeded")
            count >= maxPerSender -> RoutingDecision.Dropped("per_sender_cap_exceeded")
            else -> {
                reservations[reservationKey(sender, message)] = ReservationState.RESERVED
                null
            }
        }
    }

    /** Runs only as part of routing, under the receiver's routing/process locks. */
    private fun prune(now: Long) {
        val expired = reservationTimes.filterValues { it < now - RESERVATION_RETENTION_MILLIS }.keys
        expired.forEach { key ->
            reservations.remove(key)
            reservationTimes.remove(key)
        }
        val active = senderAcceptedAt.mapValues { (_, times) -> times.filter { it > now - senderWindowMillis } }
            .filterValues { it.isNotEmpty() }
        senderAcceptedAt.clear()
        senderAcceptedAt.putAll(active)
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
        private const val DEFAULT_SENDER_WINDOW_MILLIS = 60L * 60 * 1_000
        private const val MAX_IDENTITY_CHARS = 512
        private const val RESERVATION_RETENTION_MILLIS = 14L * 24 * 60 * 60 * 1_000

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
