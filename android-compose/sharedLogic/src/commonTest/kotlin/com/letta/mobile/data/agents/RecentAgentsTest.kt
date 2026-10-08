package com.letta.mobile.data.agents

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** The one recents rule the desktop rail and the phone drawer share (letta-mobile-c3np7.5.8). */
class RecentAgentsTest {
    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val directory = listOf("a" to "Alpha", "b" to "Beta", "c" to "Gamma", "d" to "Delta")

    private fun daysAgo(days: Int) = now - days.days

    private fun ids(cut: RecentAgentsCut) = cut.agents.map { it.first }

    @Test
    fun keepsOnlyAgentsActiveInsideTheWindowNewestFirst() {
        val cut = RecentAgents.cut(
            RecentAgentsInput(directory, lastActiveAt = mapOf("c" to daysAgo(1), "a" to daysAgo(3), "b" to daysAgo(40))),
            now,
        )
        assertEquals(listOf("c" to "Gamma", "a" to "Alpha"), cut.agents)
        assertEquals(2, cut.hiddenCount)
    }

    @Test
    fun capsTheStripAtTheMaximum() {
        val dir = (1..10).map { "agent$it" to "Agent $it" }
        val activity = (1..10).associate { "agent$it" to daysAgo(0) - it.hours }
        val cut = RecentAgents.cut(RecentAgentsInput(dir, activity), now, RecentAgentsPolicy(maxAgents = 3))
        assertEquals(listOf("agent1", "agent2", "agent3"), ids(cut))
        assertEquals(7, cut.hiddenCount)
    }

    @Test
    fun fallsBackToTheDirectoryHeadWhenNothingIsRecent() {
        val cut = RecentAgents.cut(
            RecentAgentsInput(directory, lastActiveAt = mapOf("b" to daysAgo(90))),
            now,
            RecentAgentsPolicy(maxAgents = 2),
        )
        assertEquals(listOf("a" to "Alpha", "b" to "Beta"), cut.agents)
    }

    @Test
    fun theSelectedAgentStaysEvenWhenStale() {
        val cut = RecentAgents.cut(
            RecentAgentsInput(directory, mapOf("a" to daysAgo(1), "d" to daysAgo(60)), selectedAgentId = "d"),
            now,
        )
        assertEquals(listOf("a", "d"), ids(cut))
    }

    @Test
    fun pinnedAgentsComeFirstAndDoNotCountAgainstTheCap() {
        val cut = RecentAgents.cut(
            RecentAgentsInput(
                directory,
                lastActiveAt = mapOf("a" to daysAgo(1), "b" to daysAgo(2), "c" to daysAgo(3)),
                pinnedAgentIds = setOf("d", "b"),
            ),
            now,
            RecentAgentsPolicy(maxAgents = 1),
        )
        // Pins in directory order, then one recent that is not already pinned.
        assertEquals(listOf("b", "d", "a"), ids(cut))
        assertEquals(1, cut.hiddenCount)
    }

    @Test
    fun unknownIdsAndDuplicatesAreIgnored() {
        val cut = RecentAgents.cut(
            RecentAgentsInput(
                directory + ("a" to "Alpha again"),
                lastActiveAt = mapOf("ghost" to daysAgo(0), "a" to daysAgo(1)),
                selectedAgentId = "ghost",
                pinnedAgentIds = setOf("ghost"),
            ),
            now,
        )
        assertEquals(listOf("a" to "Alpha"), cut.agents)
        assertEquals(3, cut.hiddenCount)
    }

    @Test
    fun everyAgentWindowStillCapsNewestFirst() {
        val cut = RecentAgents.cut(
            RecentAgentsInput(directory, mapOf("d" to daysAgo(400), "b" to daysAgo(200))),
            now,
            RecentAgentsPolicy.forDays(0).copy(maxAgents = 1),
        )
        assertEquals(listOf("b"), ids(cut))
        assertEquals(RecentAgentsPolicy.DEFAULT_WINDOW, RecentAgentsPolicy().window)
        assertEquals(14.days, RecentAgentsPolicy.forDays(14).window)
    }

    @Test
    fun withNoTimestampsAtAllTheStripStillHoldsTheCap() {
        val dir = (1..20).map { "agent$it" to "Agent $it" }
        listOf(RecentAgentsPolicy(), RecentAgentsPolicy(fillToCap = true)).forEach { policy ->
            val cut = RecentAgents.cut(RecentAgentsInput(dir), now, policy)
            assertEquals((1..8).map { "agent$it" }, ids(cut))
            assertEquals(12, cut.hiddenCount)
        }
        // A short roster shows every agent: min(cap, n).
        val short = RecentAgents.cut(RecentAgentsInput(directory), now, RecentAgentsPolicy(fillToCap = true))
        assertEquals(listOf("a", "b", "c", "d"), ids(short))
        assertEquals(0, short.hiddenCount)
    }

    @Test
    fun theSelectedAgentAloneDoesNotCountAsRecent() {
        // Only the focused agent has activity: the strip still falls back to the directory head.
        val dir = (1..12).map { "agent$it" to "Agent $it" }
        val cut = RecentAgents.cut(
            RecentAgentsInput(dir, mapOf("agent11" to daysAgo(0)), selectedAgentId = "agent11"),
            now,
        )
        assertEquals((1..8).map { "agent$it" } + "agent11", ids(cut))
        assertEquals(3, cut.hiddenCount)
    }

    @Test
    fun fillToCapTopsUpWithOlderActivityThenTheDirectory() {
        val dir = (1..6).map { "agent$it" to "Agent $it" }
        val cut = RecentAgents.cut(
            RecentAgentsInput(dir, mapOf("agent5" to daysAgo(1), "agent2" to daysAgo(40), "agent6" to daysAgo(90))),
            now,
            RecentAgentsPolicy(maxAgents = 5, fillToCap = true),
        )
        // In the window, then outside it newest first, then never active in directory order.
        assertEquals(listOf("agent5", "agent2", "agent6", "agent1", "agent3"), ids(cut))
        assertEquals(1, cut.hiddenCount)
    }

    @Test
    fun fillToCapKeepsPinsFirstAndTheSelectedAgentOnce() {
        val dir = (1..10).map { "agent$it" to "Agent $it" }
        val cut = RecentAgents.cut(
            RecentAgentsInput(
                dir,
                lastActiveAt = mapOf("agent3" to daysAgo(0), "agent9" to daysAgo(2)),
                selectedAgentId = "agent3",
                pinnedAgentIds = setOf("agent10"),
            ),
            now,
            RecentAgentsPolicy(maxAgents = 3, fillToCap = true),
        )
        assertEquals(listOf("agent10", "agent3", "agent9", "agent1"), ids(cut))
        assertEquals(6, cut.hiddenCount)
    }

    @Test
    fun conversationsGiveEachAgentItsNewestActivityAndSourcesMerge() {
        val fromConversations = RecentAgents.lastActiveAtFromConversations(
            listOf(
                Conversation(id = ConversationId("c1"), agentId = AgentId("a"), updatedAt = "2026-10-01T00:00:00Z"),
                Conversation(
                    id = ConversationId("c2"),
                    agentId = AgentId("a"),
                    updatedAt = "2026-10-02T00:00:00Z",
                    lastMessageAt = "2026-10-06T00:00:00Z",
                ),
                Conversation(id = ConversationId("c3"), agentId = AgentId("b"), lastMessageAt = "garbage"),
            ),
        )
        assertEquals(mapOf("a" to Instant.parse("2026-10-06T00:00:00Z")), fromConversations)
        val merged = RecentAgents.merge(fromConversations, mapOf("a" to daysAgo(30), "b" to daysAgo(2)))
        assertEquals(mapOf("a" to Instant.parse("2026-10-06T00:00:00Z"), "b" to daysAgo(2)), merged)
    }

    @Test
    fun latestByAgentKeepsEachAgentsNewestInstant() {
        val latest = RecentAgents.latestByAgent(
            listOf("a" to daysAgo(5), "a" to daysAgo(1), "b" to daysAgo(2), "" to daysAgo(0)),
        )
        assertEquals(mapOf("a" to daysAgo(1), "b" to daysAgo(2)), latest)
    }

    @Test
    fun anAgentIsActiveAtItsLaterOfLastRunAndUpdate() {
        val agents = listOf(
            Agent(id = AgentId("a"), name = "Alpha", lastRunCompletion = "2026-10-06T12:00:00Z", updatedAt = "2026-10-01T00:00:00Z"),
            Agent(id = AgentId("b"), name = "Beta", updatedAt = "2026-10-05T00:00:00Z"),
            Agent(id = AgentId("c"), name = "Gamma", updatedAt = "not a time"),
        )
        assertEquals(
            mapOf("a" to Instant.parse("2026-10-06T12:00:00Z"), "b" to Instant.parse("2026-10-05T00:00:00Z")),
            RecentAgents.lastActiveAt(agents),
        )
    }
}
