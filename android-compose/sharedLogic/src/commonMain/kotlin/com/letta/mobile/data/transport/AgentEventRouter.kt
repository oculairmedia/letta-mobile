package com.letta.mobile.data.transport

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * letta-mobile-ztuog: who a [WsTimelineEvent] belongs to, as far as the wire lets us tell.
 */
internal sealed interface EventOwner {
    /** The frame belongs to exactly this agent; no other agent's chat may see it. */
    data class Agent(val agentId: AgentId) : EventOwner

    /** Connection-wide (disconnects, goal refreshes): every agent's chat needs it. */
    data object Everyone : EventOwner

    /**
     * The frame carries no agent and names no turn, run or conversation we have seen an agent
     * claim. It is delivered to every attached chat, whose own turn state decides — the
     * pre-routing behavior, kept so an unroutable terminal can never strand a turn.
     */
    data object Unknown : EventOwner
}

/** The kinds of wire id an agent can own. */
internal enum class OwnershipKind {
    Turn,
    Run,
    Conversation,
    ;

    /** Null for an absent or blank id: such an id identifies nothing. */
    fun key(id: String?): OwnershipKey? = id?.takeIf { it.isNotBlank() }?.let { OwnershipKey(this, it) }
}

/** A turn, run or conversation id, typed so the three id spaces can never collide. */
internal data class OwnershipKey(val kind: OwnershipKind, val id: String)

/**
 * letta-mobile-ztuog: turns a frame into its owning agent.
 *
 * Only some frames name their agent (turn_started, message frames, agent_updated,
 * user_action_outcome); the rest (stop_reason, usage, turn_done, subscribe_done, error) name only
 * a turn, run or conversation. The router learns `turn/run/conversation -> agent` from every
 * agent-tagged frame and from every send, then resolves the untagged ones through that table.
 *
 * Learning is idempotent and each subscriber consults the router in frame order, so by the time
 * any subscriber resolves frame N, the keys of frames before N are known — regardless of how many
 * subscribers share the bridge.
 */
internal class AgentEventRouter(capacity: Int = DEFAULT_CAPACITY) {
    private val owners = AgentOwnerTable(capacity)

    fun ownerOf(event: WsTimelineEvent): EventOwner {
        if (event.isConnectionWide()) return EventOwner.Everyone
        val keys = event.ownershipKeys()
        val claimed = event.claimedAgent()
        if (claimed != null) {
            keys.forEach { owners.learn(it, claimed) }
            return EventOwner.Agent(claimed)
        }
        return keys.firstNotNullOfOrNull(owners::ownerOf)?.let(EventOwner::Agent) ?: EventOwner.Unknown
    }

    /** A send names its agent and conversation before any frame of the turn exists. */
    fun learnSend(agentId: AgentId, conversationId: ConversationId) {
        OwnershipKind.Conversation.key(conversationId.value)?.let { owners.learn(it, agentId) }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 512
    }
}

private fun WsTimelineEvent.isConnectionWide(): Boolean =
    this is WsTimelineEvent.Disconnected || this is WsTimelineEvent.GoalsUpdated

/** The agent the frame names itself, when it names one. */
private fun WsTimelineEvent.claimedAgent(): AgentId? = when (this) {
    is WsTimelineEvent.TurnStarted -> agentId
    is WsTimelineEvent.AgentUpdated -> agentId
    is WsTimelineEvent.MessageDelta -> agentId
    is WsTimelineEvent.UserActionOutcome -> agentId
    else -> null
}?.takeIf { it.isNotBlank() }?.let(::AgentId)

/** The ids the frame carries, most specific first: turn, then run, then conversation. */
private fun WsTimelineEvent.ownershipKeys(): List<OwnershipKey> = when (this) {
    is WsTimelineEvent.TurnStarted -> listOfNotNull(turn(turnId), run(runId), conversation(conversationId))
    is WsTimelineEvent.MessageDelta -> listOfNotNull(turn(turnId), run(message.runId), conversation(conversationId))
    is WsTimelineEvent.UserActionOutcome -> listOfNotNull(turn(turnId), run(runId), conversation(conversationId))
    is WsTimelineEvent.Error -> listOfNotNull(turn(turnId), run(runId), conversation(conversationId))
    is WsTimelineEvent.StopReason -> listOfNotNull(turn(turnId), run(runId))
    is WsTimelineEvent.UsageStatistics -> listOfNotNull(turn(turnId), run(runId))
    is WsTimelineEvent.TurnDone -> listOfNotNull(turn(turnId), run(runId))
    is WsTimelineEvent.SubscribeDone -> listOfNotNull(run(runId))
    else -> emptyList()
}

private fun turn(id: String?) = OwnershipKind.Turn.key(id)
private fun run(id: String?) = OwnershipKind.Run.key(id)
private fun conversation(id: String?) = OwnershipKind.Conversation.key(id)

/**
 * Bounded `key -> agent` table. A key two agents have claimed (the bare `default` conversation
 * shared by an agent and its subagent) is ambiguous and resolves to nobody, so the frame falls
 * back to [EventOwner.Unknown] instead of being handed to the wrong agent.
 */
internal class AgentOwnerTable(private val capacity: Int) {
    private val lock = SynchronizedObject()

    /** A null owner marks the key ambiguous. */
    private val owners = LinkedHashMap<OwnershipKey, AgentId?>()

    fun learn(key: OwnershipKey, agentId: AgentId) = synchronized(lock) {
        val known = owners.containsKey(key)
        val previous = owners.remove(key)
        owners[key] = agentId.takeUnless { known && previous != agentId }
        while (owners.size > capacity) owners.remove(owners.keys.first())
    }

    fun ownerOf(key: OwnershipKey): AgentId? = synchronized(lock) { owners[key] }
}
