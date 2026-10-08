package com.letta.mobile.desktop

import com.letta.mobile.data.agents.RecentAgents
import com.letta.mobile.data.agents.RecentAgentsCut
import com.letta.mobile.data.agents.RecentAgentsInput
import com.letta.mobile.data.agents.RecentAgentsPolicy
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** The desktop rail's binding of the shared recents cut (letta-mobile-c3np7.5.8). */
class DesktopRailRecencyTest {
    private val now: Instant = Instant.parse("2026-09-16T20:00:00Z")

    private fun conversation(id: String, agentId: String, daysAgo: Long, label: String? = null) = DesktopConversationSummary(
        id = id,
        title = "Conversation $id",
        agentName = "Agent $agentId",
        updatedAtLabel = label ?: (now - daysAgo.days).toString(),
        lastMessagePreview = "preview",
        agentId = agentId,
        archived = false,
    )

    private val directory = listOf("a" to "Alpha", "b" to "Beta", "c" to "Gamma", "d" to "Delta")

    /** The cut [rememberRecentRailAgents] takes, at a fixed [now]. */
    private fun recentRailAgents(
        conversations: List<DesktopConversationSummary>,
        directory: List<Pair<String, String>>,
        policy: RecentAgentsPolicy = RecentAgentsPolicy(),
        selectedAgentId: String? = null,
    ): RecentAgentsCut = RecentAgents.cut(
        RecentAgentsInput(directory, railAgentActivity(conversations), selectedAgentId),
        now,
        policy,
    )

    @Test
    fun `keeps only agents used inside the window, newest first`() {
        val conversations = listOf(
            conversation("1", "c", daysAgo = 1),
            conversation("2", "a", daysAgo = 3),
            conversation("3", "b", daysAgo = 40),
        )
        val rail = recentRailAgents(conversations, directory)
        assertEquals(listOf("c" to "Gamma", "a" to "Alpha"), rail.agents)
        assertEquals(2, rail.hiddenCount)
    }

    @Test
    fun `an agent ranks by its newest conversation`() {
        val conversations = listOf(
            conversation("1", "a", daysAgo = 5),
            conversation("2", "b", daysAgo = 2),
            conversation("3", "a", daysAgo = 1),
        )
        assertEquals(listOf("a", "b"), recentRailAgents(conversations, directory).agents.map { it.first })
    }

    @Test
    fun `queued work is newest and an unreadable label is never recent`() {
        val conversations = listOf(
            conversation("1", "a", daysAgo = 1),
            conversation("2", "b", daysAgo = 0, label = "Queued"),
            conversation("3", "c", daysAgo = 0, label = "yesterday-ish"),
        )
        assertEquals(listOf("b", "a"), recentRailAgents(conversations, directory).agents.map { it.first })
    }

    @Test
    fun `caps the rail at the maximum`() {
        val conversations = (1..10).map { conversation("$it", "agent$it", daysAgo = it.toLong()) }
        val dir = (1..10).map { "agent$it" to "Agent $it" }
        val rail = recentRailAgents(conversations, dir, RecentAgentsPolicy(maxAgents = 3))
        assertEquals(listOf("agent1", "agent2", "agent3"), rail.agents.map { it.first })
        assertEquals(7, rail.hiddenCount)
    }

    @Test
    fun `falls back to the directory head when nothing is recent`() {
        val conversations = listOf(conversation("1", "b", daysAgo = 90))
        val rail = recentRailAgents(conversations, directory, RecentAgentsPolicy(maxAgents = 2))
        assertEquals(listOf("a" to "Alpha", "b" to "Beta"), rail.agents)
    }

    @Test
    fun `the selected agent stays on the rail even when stale`() {
        val conversations = listOf(
            conversation("1", "a", daysAgo = 1),
            conversation("2", "d", daysAgo = 60),
        )
        val rail = recentRailAgents(conversations, directory, selectedAgentId = "d")
        assertEquals(listOf("a" to "Alpha", "d" to "Delta"), rail.agents)
    }

    @Test
    fun `only the selected agent being recent still fills the rail from the directory`() {
        val conversations = listOf(conversation("1", "c", daysAgo = 0))
        val rail = recentRailAgents(conversations, directory, RecentAgentsPolicy(maxAgents = 2), selectedAgentId = "c")
        assertEquals(listOf("a", "b", "c"), rail.agents.map { it.first })
        assertEquals(1, rail.hiddenCount)
    }

    @Test
    fun `the every-agent preference widens the window but keeps the cap`() {
        val conversations = listOf(conversation("1", "d", daysAgo = 400), conversation("2", "b", daysAgo = 200))
        val rail = recentRailAgents(conversations, directory, RecentAgentsPolicy.forDays(0))
        assertEquals(listOf("b", "d"), rail.agents.map { it.first })
    }
}
