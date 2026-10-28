package com.letta.mobile.util

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import com.letta.mobile.data.model.ConversationRecency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every conversation list reads the fleet agent by agent, so worker transcripts never crowd a user's chats out. */
class AgentConversationFleetTest {
    private val nora = Agent(id = AgentId("agent-nora"), name = "Nora")
    private val ivo = Agent(id = AgentId("agent-ivo"), name = "Ivo")

    @Test
    fun anAgentsOwnConversationsListPastTheHostsAgentFreeNoiseAndPageToMore() = runTest {
        // The affected host: its newest 100 are 98 agent-free worker rows and 2 of Nora's; she has 60 in all.
        val host = Host(
            rows = List(98) { row("worker-$it", agent = null, at = "2026-10-22T10:00:00Z") } +
                List(60) { row("nora-${pad(it)}", nora.id.value, at = "2026-10-${pad(20 - it / 3)}T00:00:00Z") },
        )
        val fleet = AgentConversationFleet(host, AgentConversationFleetPaging(pageSize = 50))

        val first = fleet.refresh(listOf(nora))

        assertEquals(50, first.size)
        assertTrue(first.all { it.agentId == nora.id })
        assertTrue(fleet.hasMore)
        val all = fleet.loadMore()
        assertEquals(60, all.size)
        assertFalse(fleet.hasMore)
        assertEquals(listOf(null, ConversationId("nora-49")), host.reads.map { it.after })
        assertTrue(host.reads.all { it.agent == nora.id }, "no read across every agent")
    }

    @Test
    fun aReadThroughRefreshListsEveryPageOfEveryAgentNewestFirst() = runTest {
        val host = Host(
            rows = List(3) { row("n$it", nora.id.value, at = "2026-10-0${it + 1}T00:00:00Z") } +
                row("i0", ivo.id.value, at = "2026-10-02T12:00:00Z"),
        )
        val fleet = AgentConversationFleet(host, AgentConversationFleetPaging(pageSize = 2, eagerPagesPerAgent = 5))

        val listed = fleet.refresh(listOf(nora, ivo))

        assertEquals(listOf("n2", "i0", "n1", "n0"), listed.map { it.id.value })
        assertFalse(fleet.hasMore)
    }

    @Test
    fun hiddenSubagentsAreNotReadAndOtherAgentsRowsAreNotListed() = runTest {
        val subagent = Agent(id = AgentId("agent-sub"), name = "Worker", hidden = true)
        val host = Host(rows = listOf(row("n0", nora.id.value), row("stray", ivo.id.value)), ignoresAgentFilter = true)
        val fleet = AgentConversationFleet(host)

        val listed = fleet.refresh(listOf(nora, subagent))

        assertEquals(listOf("n0"), listed.map { it.id.value })
        assertEquals(listOf(nora.id), host.reads.map { it.agent })
    }

    @Test
    fun aHiddenRowInAFullPageDoesNotEndPaging() = runTest {
        val hidden = Conversation(id = ConversationId("n1"), agentId = nora.id, hidden = true)
        val host = Host(rows = listOf(row("n0", nora.id.value), nora.id.value to hidden, row("n2", nora.id.value)))
        val fleet = AgentConversationFleet(host, AgentConversationFleetPaging(pageSize = 2, eagerPagesPerAgent = 5))

        val listed = fleet.refresh(listOf(nora))

        assertEquals(setOf("n0", "n2"), listed.map { it.id.value }.toSet())
        assertEquals(listOf(null, ConversationId("n1")), host.reads.map { it.after })
    }

    @Test
    fun oneAgentsFailedReadKeepsItsLastListAndTheOthersListed() = runTest {
        val host = Host(rows = listOf(row("n0", nora.id.value), row("i0", ivo.id.value)))
        val fleet = AgentConversationFleet(host)
        fleet.refresh(listOf(nora, ivo))

        host.failing += ivo.id
        val listed = fleet.refresh(listOf(nora, ivo))

        assertEquals(setOf("n0", "i0"), listed.map { it.id.value }.toSet())
    }

    @Test
    fun whenEveryReadFailsTheRefreshFails() = runTest {
        val host = Host(rows = emptyList()).apply { failing += nora.id }

        assertFailsWith<IllegalStateException> { AgentConversationFleet(host).refresh(listOf(nora)) }
    }

    @Test
    fun anEmptyRosterListsNothingWithoutAnyRead() = runTest {
        val host = Host(rows = listOf(row("n0", nora.id.value)))

        assertTrue(AgentConversationFleet(host).refresh(emptyList()).isEmpty())
        assertTrue(host.reads.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun workerOnlyGlobalWindowsAndHeavyHistoryDoNotLoseTheRosterRows() = runTest {
        repeat(5) { sample ->
            val agents = List(137) { Agent(AgentId("agent-$it"), name = "Agent $it") }
            val visible = List(501) { row("heavy-${it.toString().padStart(3, '0')}", agents.first().id.value) } +
                List(382) { row("other-$it", agents[1 + it % 136].id.value) }
            val workers = List(5_000) { row("worker-$it", null, "2026-10-22T10:00:00Z") }
            val host = Host(workers + visible)
            val fleet = AgentConversationFleet(
                AgentConversationReader { agent, after, limit ->
                    delay(100)
                    host.read(agent, after, limit)
                },
                AgentConversationFleetPaging(eagerPagesPerAgent = READ_THROUGH_PAGES),
            )
            val start = currentTime
            val initial = fleet.refresh(agents)
            // The existing eager bound is not completeness: 501 rows need an eleventh page.
            assertEquals(882, initial.size)
            assertTrue(fleet.hasMore)
            val all = fleet.loadMore()
            assertEquals(883, all.size)
            assertFalse(fleet.hasMore)
            assertEquals(visible.map { it.second }.sortedWith(ConversationRecency.newestFirst), all)
            assertEquals(147, host.reads.size)
            assertTrue(host.reads.all { it.agent in agents.map(Agent::id) })
            println("heavy sample=$sample requests=${host.reads.size} complete=${currentTime - start} rows=${all.size} workerRows=5000")

            // Comparative full-scan model only, not a replacement gateway: a single global window
            // contains no roster rows. A complete scan is correct but pays for every worker window.
            val globalStart = currentTime
            var globalCalls = 0
            val global = mutableListOf<Conversation>()
            (workers + visible).chunked(50).forEach { page ->
                globalCalls++
                delay(100)
                global += page.filter { it.first != null }.map { it.second }
            }
            assertEquals(all, global.sortedWith(ConversationRecency.newestFirst))
            assertEquals(118, globalCalls)
            assertEquals(11_800, currentTime - globalStart)
            println("global-model sample=$sample requests=$globalCalls complete=${currentTime - globalStart} rows=${global.size}")
        }
    }

    @Test
    fun cancellingARefreshReleasesTheFleetAndPublishesNoPartialRoster() = runTest {
        val started = CompletableDeferred<Unit>()
        var waiting = true
        val fleet = AgentConversationFleet(AgentConversationReader { _, _, _ ->
            if (waiting) {
                started.complete(Unit)
                awaitCancellation()
            }
            listOf(row("n0", nora.id.value).second)
        })
        val refresh = launch { fleet.refresh(listOf(nora)) }
        started.await()
        refresh.cancelAndJoin()
        assertTrue(fleet.conversations.isEmpty())
        waiting = false
        assertEquals(listOf("n0"), fleet.refresh(listOf(nora)).map { it.id.value })
    }

    /** Rows as a host's App Server lists them (worker rows have no agent and match no agent's read). */
    private class Host(
        private val rows: List<Pair<String?, Conversation>>,
        private val ignoresAgentFilter: Boolean = false,
    ) : AgentConversationReader {
        val reads = mutableListOf<Read>()
        val failing = mutableSetOf<AgentId>()

        override suspend fun read(agent: AgentId, after: ConversationId?, limit: Int): List<Conversation> {
            reads += Read(agent, after)
            check(agent !in failing) { "${agent.value} unreadable" }
            val own = rows.filter { (owner, _) -> owner != null && (ignoresAgentFilter || owner == agent.value) }.map { it.second }
            val from = after?.let { cursor -> own.indexOfFirst { it.id == cursor } + 1 } ?: 0
            return own.drop(from).take(limit)
        }
    }

    private data class Read(val agent: AgentId, val after: ConversationId?)

    private fun row(id: String, agent: String?, at: String? = null): Pair<String?, Conversation> =
        agent to Conversation(id = ConversationId(id), agentId = AgentId(agent ?: "unused"), lastMessageAt = at)
}

private fun pad(value: Int): String = value.toString().padStart(2, '0')
