package com.letta.mobile.util

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * One fleet-wide read: each agent's value in roster order, and the agents whose read failed.
 * One agent's failure leaves the others' values; only [allFailed] means the read as a whole failed.
 */
data class AgentFleetRead<K, out T>(
    /** Each agent whose read answered, with its value, in roster order. */
    val values: List<Pair<K, T>>,
    /** Each agent whose read failed, with its failure, in roster order. */
    val failures: Map<K, Throwable>,
) {
    /** Some agent was read and every read failed. */
    val allFailed: Boolean get() = values.isEmpty() && failures.isNotEmpty()

    /** The first failure in roster order, or null when every read answered. */
    val firstFailure: Throwable? get() = failures.values.firstOrNull()
}

/**
 * Reads something per agent across the roster, a few agents at a time, the way
 * [AgentConversationFleet] reads conversations: for pages (Schedules, Memory) whose host serves
 * the data only per agent and that show the whole fleet when no agent is focused.
 */
object AgentFleetReads {
    /** Reads at once across the roster. */
    const val DEFAULT_PARALLELISM: Int = 4

    /** Reads [agents] (an agent id each; duplicates once) with at most [parallelism] reads in flight. */
    suspend fun <K, T> read(
        agents: List<K>,
        parallelism: Int = DEFAULT_PARALLELISM,
        read: suspend (K) -> T,
    ): AgentFleetRead<K, T> = coroutineScope {
        val permits = Semaphore(parallelism.coerceAtLeast(1))
        val results = agents.distinct().map { agent ->
            async { agent to runCatchingCancellable { permits.withPermit { read(agent) } } }
        }.awaitAll()
        AgentFleetRead(
            values = results.filter { it.second.isSuccess }.map { (agent, result) -> agent to result.getOrThrow() },
            failures = results.mapNotNull { (agent, result) -> result.exceptionOrNull()?.let { agent to it } }.toMap(),
        )
    }
}
