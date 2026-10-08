package com.letta.mobile.data.agents

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Conversation
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** How far back, and how many agents, a recents strip reaches. */
data class RecentAgentsPolicy(
    val window: Duration = DEFAULT_WINDOW,
    val maxAgents: Int = DEFAULT_MAX_AGENTS,
    /**
     * Keep the strip full: when fewer than [maxAgents] agents were active inside [window], the next
     * most recently active agents top it up, then the directory head (agents with no activity at
     * all). The phone's drawer uses it because an agent's own record is a thin activity signal; the
     * desktop rail leaves it off so its recency-window preference still trims the strip.
     */
    val fillToCap: Boolean = false,
) {
    companion object {
        val DEFAULT_WINDOW: Duration = 7.days
        const val DEFAULT_MAX_AGENTS = 8

        /** A window nothing falls outside: "every agent", still newest first and still capped. */
        val EVERY_AGENT_WINDOW: Duration = 36_500.days

        /** The policy for a window of [days] days; `0` means every agent. */
        fun forDays(days: Int): RecentAgentsPolicy =
            RecentAgentsPolicy(window = if (days <= 0) EVERY_AGENT_WINDOW else days.days)
    }
}

/** What a recents strip is cut from. */
data class RecentAgentsInput(
    /** Every agent as (id, name); its order is the fallback order when nothing is recent. */
    val directory: List<Pair<String, String>>,
    /** Each agent's latest activity. An agent with none is never recent. */
    val lastActiveAt: Map<String, Instant> = emptyMap(),
    /** The focused agent: kept even when stale, so a pick from the full list shows up. */
    val selectedAgentId: String? = null,
    /** Pinned (and favourite) agents: always kept, ahead of the recents. */
    val pinnedAgentIds: Set<String> = emptySet(),
)

/** The strip: the agents it shows, in order, and how many of the directory it leaves out. */
data class RecentAgentsCut(
    val agents: List<Pair<String, String>>,
    /** Directory agents not on the strip; the host offers an "all agents" path while this is non-zero. */
    val hiddenCount: Int,
)

/**
 * The agent rail is a recents strip, not the whole roster: one rule for the desktop rail and the
 * phone's navigation drawer (letta-mobile-c3np7.5.8).
 *
 * - Pinned agents come first, in directory order, and are always kept.
 * - Then the agents active inside [RecentAgentsPolicy.window], newest first, capped at
 *   [RecentAgentsPolicy.maxAgents] (pins do not count against the cap).
 * - With nothing recent besides the selected agent (a fresh install, or a host whose activity
 *   signal is thin), the head of the directory fills the strip so it is never just one orb.
 *   [RecentAgentsPolicy.fillToCap] goes further and always tops the strip up to the cap.
 * - The selected agent is appended when it fell off the cut.
 *
 * Everything else stays reachable through the host's full agent list; [RecentAgentsCut.hiddenCount]
 * says how much that is.
 */
object RecentAgents {
    fun cut(input: RecentAgentsInput, now: Instant, policy: RecentAgentsPolicy = RecentAgentsPolicy()): RecentAgentsCut {
        val nameById = LinkedHashMap<String, String>()
        input.directory.forEach { (id, name) -> if (id !in nameById) nameById[id] = name }
        val pinned = nameById.keys.filter { it in input.pinnedAgentIds }
        val candidates = nameById.keys.filter { it !in input.pinnedAgentIds }
        val kept = LinkedHashSet(pinned)
        kept += strip(candidates, input, now, policy).take(policy.maxAgents.coerceAtLeast(0))
        input.selectedAgentId?.takeIf { it in nameById }?.let(kept::add)
        return RecentAgentsCut(
            agents = kept.map { it to nameById.getValue(it) },
            hiddenCount = nameById.size - kept.size,
        )
    }

    /** The unpinned agents in strip order, before the cap. */
    private fun strip(candidates: List<String>, input: RecentAgentsInput, now: Instant, policy: RecentAgentsPolicy): List<String> {
        // Stable: agents with the same (or no) activity keep their directory order, and no activity sorts last.
        val byRecency = candidates.sortedByDescending { input.lastActiveAt[it] }
        if (policy.fillToCap) return byRecency
        val cutoff = now - policy.window
        val recent = byRecency.filter { id -> input.lastActiveAt[id]?.let { it >= cutoff } == true }
        // The selected agent is kept anyway, so on its own it does not make the strip "recent".
        return if (recent.any { it != input.selectedAgentId }) recent else candidates
    }

    /** The newest instant per agent, from (agent id, instant) activity such as conversations. */
    fun latestByAgent(activity: Iterable<Pair<String, Instant>>): Map<String, Instant> =
        activity
            .filter { (id, _) -> id.isNotBlank() }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
            .mapValues { (_, instants) -> instants.max() }

    /** Each agent's latest activity from its own record: the later of its last run and its last update. */
    fun lastActiveAt(agents: Iterable<Agent>): Map<String, Instant> =
        latestByAgent(
            agents.flatMap { agent ->
                listOfNotNull(agent.lastRunCompletion, agent.updatedAt)
                    .mapNotNull(::parseInstant)
                    .map { agent.id.value to it }
            },
        )

    /** Each agent's latest activity from its conversations: the newest last message or update among them. */
    fun lastActiveAtFromConversations(conversations: Iterable<Conversation>): Map<String, Instant> =
        latestByAgent(
            conversations.flatMap { conversation ->
                listOfNotNull(conversation.lastMessageAt, conversation.updatedAt)
                    .mapNotNull(::parseInstant)
                    .map { conversation.agentId.value to it }
            },
        )

    /** Several activity sources merged: each agent at its newest instant across all of them. */
    fun merge(vararg sources: Map<String, Instant>): Map<String, Instant> =
        latestByAgent(sources.flatMap { source -> source.map { (id, at) -> id to at } })

    private fun parseInstant(raw: String): Instant? = runCatching { Instant.parse(raw) }.getOrNull()
}
