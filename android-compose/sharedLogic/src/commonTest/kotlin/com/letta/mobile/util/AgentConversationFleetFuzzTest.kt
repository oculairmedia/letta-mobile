package com.letta.mobile.util

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ConversationRecency
import com.letta.mobile.data.model.visibleInLists
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test

/**
 * Seeded property tests for [AgentConversationFleet] against fixture-derived expectations.
 *
 * Supported: visible agents only, per-agent cursor paging, hidden conversation rows filtered from
 * listings but still advancing cursors, empty and hidden-only pages, exact full final pages.
 * Excluded: worker/agent-free rows (never read), archive-only filtering (archived rows remain listable),
 * cancellation mid-read (no reliable virtual-time seam in this slice).
 */
class AgentConversationFleetFuzzTest {
    private val recencyOrder = compareByDescending<Conversation> {
        ConversationRecency.sortKeyMillis(it.lastMessageAt, it.updatedAt, it.createdAt)
    }.thenBy { it.id.value }

    @Test
    fun seededFleetPaginationMatchesFixtureExpectations() = runTest {
        var totalCases = 0
        FuzzCorpus.paginationSeeds.forEach { seed ->
            repeat(FuzzCorpus.SCENARIOS_PER_SEED) { scenario ->
                totalCases++
                runScenario(seed, scenario, scenarioRandom(seed, scenario))
            }
        }
        check(totalCases == FuzzCorpus.paginationSeeds.size * FuzzCorpus.SCENARIOS_PER_SEED) {
            "expected ${FuzzCorpus.paginationSeeds.size * FuzzCorpus.SCENARIOS_PER_SEED} cases, got $totalCases"
        }
    }

    private suspend fun runScenario(seed: Long, scenarioIndex: Int, random: Random) {
        val agentCount = when {
            scenarioIndex % 17 == 0 -> 1
            else -> random.nextInt(1, 5)
        }
        val agents = List(agentCount) { index ->
            val hidden = random.nextBoolean() && index > 0
            Agent(
                id = AgentId("agent-$seed-$scenarioIndex-$index"),
                name = "A$index",
                hidden = if (hidden) true else null,
            )
        }
        val visibleAgents = agents.visibleInLists()
        val pageSize = random.nextInt(1, 8)
        val eagerPages = random.nextInt(1, 4)
        val store = mutableMapOf<AgentId, MutableList<Conversation>>()
        visibleAgents.forEach { agent ->
            val rowCount = when {
                scenarioIndex % 7 == 0 -> 0
                scenarioIndex % 11 == 0 && agent == visibleAgents.first() -> random.nextInt(3, 10)
                else -> random.nextInt(0, 25)
            }
            val rows = buildAgentRows(seed, scenarioIndex, agent.id, rowCount, random, scenarioIndex % 11 == 0)
            store[agent.id] = rows.toMutableList()
        }
        val backend = PagingBackend(store)
        val fleet = AgentConversationFleet(
            backend,
            AgentConversationFleetPaging(pageSize = pageSize, parallelism = 3, eagerPagesPerAgent = eagerPages),
        )
        val maxLoadMore = loadMoreBudget(visibleAgents, store, pageSize, eagerPages)
        var listed = fleet.refresh(agents)
        var loadMoreSteps = 0
        while (fleet.hasMore) {
            checkFuzz(seed, scenarioIndex, condition = loadMoreSteps < maxLoadMore) {
                "exceeded loadMore budget=$maxLoadMore steps after ${loadMoreSteps} fetches=${backend.totalFetches}"
            }
            listed = fleet.loadMore()
            loadMoreSteps++
        }
        checkFuzz(seed, scenarioIndex, condition = !fleet.hasMore) {
            "hasMore still true after budget=$maxLoadMore fetches=${backend.totalFetches}"
        }
        val listedIds = listed.map { it.id }
        checkFuzz(seed, scenarioIndex, condition = listedIds.size == listedIds.distinct().size) {
            "duplicate conversation ids in fleet output: ${listedIds.groupingBy { it }.eachCount().filter { it.value > 1 }}"
        }
        checkFuzz(seed, scenarioIndex, condition = listed == listed.sortedWith(recencyOrder)) {
            "fleet output not sorted by recency policy (sortKeyMillis then id)"
        }
        val expectedVisible = expectedVisibleIds(visibleAgents, store)
        checkFuzz(seed, scenarioIndex, condition = listedIds.toSet() == expectedVisible) {
            "membership mismatch listed=${listedIds.size} expectedVisible=${expectedVisible.size}"
        }
    }

    private fun buildAgentRows(
        seed: Long,
        scenarioIndex: Int,
        agentId: AgentId,
        rowCount: Int,
        random: Random,
        hiddenLeadingPage: Boolean,
    ): List<Conversation> {
        if (rowCount == 0) return emptyList()
        val rows = List(rowCount) { rowIndex ->
            val forceHidden = hiddenLeadingPage && rowIndex < 2
            conversationRow(
                id = "c-$seed-$scenarioIndex-${agentId.value}-$rowIndex",
                agentId = agentId,
                random = random,
                hidden = forceHidden || random.nextInt(10) == 0,
            )
        }
        return rows.sortedWith(recencyOrder)
    }

    /** Every list-visible row stored for visible agents (hidden rows excluded). */
    private fun expectedVisibleIds(
        visibleAgents: List<Agent>,
        store: Map<AgentId, List<Conversation>>,
    ): Set<ConversationId> =
        visibleAgents.flatMap { agent ->
            store[agent.id].orEmpty()
                .filter { it.agentId == agent.id && it.hidden != true }
                .map { it.id }
        }.toSet()

    /**
     * Worst-case loadMore steps: sum per agent of pages still unread after refresh eager pass.
     */
    private fun loadMoreBudget(
        visibleAgents: List<Agent>,
        store: Map<AgentId, List<Conversation>>,
        pageSize: Int,
        eagerPages: Int,
    ): Int =
        visibleAgents.sumOf { agent ->
            val rowCount = store[agent.id]?.size ?: 0
            val pagesNeeded = if (rowCount == 0) 1 else (rowCount + pageSize - 1) / pageSize
            (pagesNeeded - eagerPages).coerceAtLeast(0)
        } + 1

    private fun conversationRow(
        id: String,
        agentId: AgentId,
        random: Random,
        hidden: Boolean,
    ): Conversation {
        val day = random.nextInt(1, 29).toString().padStart(2, '0')
        val hour = random.nextInt(0, 24).toString().padStart(2, '0')
        val stamp = "2026-03-${day}T${hour}:00:00Z"
        return Conversation(
            id = ConversationId(id),
            agentId = agentId,
            lastMessageAt = if (random.nextBoolean()) stamp else null,
            updatedAt = if (random.nextBoolean()) stamp else null,
            createdAt = stamp,
            hidden = if (hidden) true else null,
            archived = if (random.nextInt(20) == 0) true else null,
        )
    }

    private class PagingBackend(
        private val store: Map<AgentId, List<Conversation>>,
    ) : AgentConversationReader {
        var totalFetches: Int = 0

        override suspend fun read(agent: AgentId, after: ConversationId?, limit: Int): List<Conversation> {
            totalFetches++
            val rows = store[agent].orEmpty()
            val start = after?.let { cursor -> rows.indexOfFirst { it.id == cursor } + 1 } ?: 0
            return rows.drop(start.coerceAtLeast(0)).take(limit)
        }
    }
}
