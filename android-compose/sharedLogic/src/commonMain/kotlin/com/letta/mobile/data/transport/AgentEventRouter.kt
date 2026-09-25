package com.letta.mobile.data.transport

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * letta-mobile-ztuog: who a [WsTimelineEvent] belongs to, as far as the wire lets us tell.
 */
internal sealed interface EventOwner {
    /** The frame belongs to exactly this agent; no other agent's chat may see it. */
    data class Agent(val agentId: String) : EventOwner

    /** Connection-wide (disconnects, goal refreshes): every agent's chat needs it. */
    data object Everyone : EventOwner

    /**
     * The frame carries no agent and names no turn, run or conversation we have seen an agent
     * claim. It is delivered to every attached chat, whose own turn state decides — the
     * pre-routing behavior, kept so an unroutable terminal can never strand a turn.
     */
    data object Unknown : EventOwner
}

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
        learn(event)
        return resolve(event)
    }

    /** A send names its agent and conversation before any frame of the turn exists. */
    fun learnSend(agentId: String, conversationId: String) {
        owners.learn(conversationKey(conversationId), agentId)
    }

    private fun learn(event: WsTimelineEvent) {
        when (event) {
            is WsTimelineEvent.TurnStarted ->
                learnKeys(event.agentId, event.turnId, event.runId, event.conversationId)
            is WsTimelineEvent.MessageDelta -> event.agentId?.let { agent ->
                learnKeys(agent, event.turnId, event.message.runId, event.conversationId)
            }
            is WsTimelineEvent.UserActionOutcome -> event.agentId?.let { agent ->
                learnKeys(agent, event.turnId, event.runId, event.conversationId)
            }
            else -> Unit
        }
    }

    private fun learnKeys(agentId: String, turnId: String?, runId: String?, conversationId: String?) {
        owners.learn(turnKey(turnId), agentId)
        owners.learn(runKey(runId), agentId)
        owners.learn(conversationKey(conversationId), agentId)
    }

    private fun resolve(event: WsTimelineEvent): EventOwner = when (event) {
        is WsTimelineEvent.TurnStarted -> EventOwner.Agent(event.agentId)
        is WsTimelineEvent.AgentUpdated -> EventOwner.Agent(event.agentId)
        is WsTimelineEvent.MessageDelta -> tagged(event.agentId)
            ?: lookup(turnKey(event.turnId), runKey(event.message.runId), conversationKey(event.conversationId))
        is WsTimelineEvent.UserActionOutcome -> tagged(event.agentId)
            ?: lookup(turnKey(event.turnId), runKey(event.runId), conversationKey(event.conversationId))
        is WsTimelineEvent.StopReason -> lookup(turnKey(event.turnId), runKey(event.runId))
        is WsTimelineEvent.UsageStatistics -> lookup(turnKey(event.turnId), runKey(event.runId))
        is WsTimelineEvent.TurnDone -> lookup(turnKey(event.turnId), runKey(event.runId))
        is WsTimelineEvent.SubscribeDone -> lookup(runKey(event.runId))
        is WsTimelineEvent.Error ->
            lookup(turnKey(event.turnId), runKey(event.runId), conversationKey(event.conversationId))
        is WsTimelineEvent.Disconnected,
        is WsTimelineEvent.GoalsUpdated,
        -> EventOwner.Everyone
    }

    private fun tagged(agentId: String?): EventOwner? =
        agentId?.takeIf { it.isNotBlank() }?.let(EventOwner::Agent)

    private fun lookup(vararg keys: String?): EventOwner =
        keys.firstNotNullOfOrNull { owners.ownerOf(it) }?.let(EventOwner::Agent) ?: EventOwner.Unknown

    private companion object {
        const val DEFAULT_CAPACITY = 512

        fun turnKey(id: String?) = id?.takeIf { it.isNotBlank() }?.let { "t:$it" }
        fun runKey(id: String?) = id?.takeIf { it.isNotBlank() }?.let { "r:$it" }
        fun conversationKey(id: String?) = id?.takeIf { it.isNotBlank() }?.let { "c:$it" }
    }
}

/**
 * Bounded `key -> agent` table. A key two agents have claimed (the bare `default` conversation
 * shared by an agent and its subagent) is ambiguous and resolves to nobody, so the frame falls
 * back to [EventOwner.Unknown] instead of being handed to the wrong agent.
 */
internal class AgentOwnerTable(private val capacity: Int) {
    private val lock = SynchronizedObject()
    private val owners = LinkedHashMap<String, String>()

    fun learn(key: String?, agentId: String) {
        if (key == null || agentId.isBlank()) return
        synchronized(lock) {
            val known = owners.remove(key)
            owners[key] = if (known == null || known == agentId) agentId else AMBIGUOUS
            while (owners.size > capacity) owners.remove(owners.keys.first())
        }
    }

    fun ownerOf(key: String?): String? {
        if (key == null) return null
        return synchronized(lock) { owners[key] }?.takeUnless { it == AMBIGUOUS }
    }

    private companion object {
        const val AMBIGUOUS = "\u0000ambiguous"
    }
}
