package com.letta.mobile.data.agents

import com.letta.mobile.data.model.Agent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** How far back, and how many agents, a recents strip reaches. */
data class RecentAgentsPolicy(
    val window: Duration = DEFAULT_WINDOW,
    val maxAgents: Int = DEFAULT_MAX_AGENTS,
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
 * - With nothing recent (a fresh install), the head of the directory fills the strip so it is never
 *   empty.
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
        val cutoff = now - policy.window
        val recent = input.lastActiveAt.entries
            .filter { (id, at) -> id in nameById && id !in input.pinnedAgentIds && at >= cutoff }
            .sortedByDescending { it.value }
            .map { it.key }
        val fill = recent.ifEmpty { nameById.keys.filter { it !in input.pinnedAgentIds } }
        val kept = LinkedHashSet(pinned)
        kept += fill.take(policy.maxAgents.coerceAtLeast(0))
        input.selectedAgentId?.takeIf { it in nameById }?.let(kept::add)
        return RecentAgentsCut(
            agents = kept.map { it to nameById.getValue(it) },
            hiddenCount = nameById.size - kept.size,
        )
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

    private fun parseInstant(raw: String): Instant? = runCatching { Instant.parse(raw) }.getOrNull()
}
