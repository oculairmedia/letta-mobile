package com.letta.mobile.desktop

import com.letta.mobile.desktop.chat.DesktopConversationSummary
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopRailRecencyTest {
    private val now: Instant = Instant.parse("2026-09-16T20:00:00Z")

    private fun conversation(id: String, agentId: String, daysAgo: Long) = DesktopConversationSummary(
        id = id,
        title = "Conversation $id",
        agentName = "Agent $agentId",
        updatedAtLabel = now.minus(Duration.ofDays(daysAgo)).toString(),
        lastMessagePreview = "preview",
        agentId = agentId,
        archived = false,
    )

    private val directory = listOf("a" to "Alpha", "b" to "Beta", "c" to "Gamma", "d" to "Delta")

    @Test
    fun `keeps only agents used inside the window, newest first`() {
        val conversations = listOf(
            conversation("1", "c", daysAgo = 1),
            conversation("2", "a", daysAgo = 3),
            conversation("3", "b", daysAgo = 40),
        )
        val rail = recentRailAgents(conversations, directory, RailRecencyPolicy(now = now))
        assertEquals(listOf("c" to "Gamma", "a" to "Alpha"), rail)
    }

    @Test
    fun `caps the rail at the maximum`() {
        val conversations = (1..10).map { conversation("$it", "agent$it", daysAgo = it.toLong()) }
        val dir = (1..10).map { "agent$it" to "Agent $it" }
        val rail = recentRailAgents(conversations, dir, RailRecencyPolicy(now = now, maxAgents = 3))
        assertEquals(listOf("agent1", "agent2", "agent3"), rail.map { it.first })
    }

    @Test
    fun `falls back to the directory head when nothing is recent`() {
        val conversations = listOf(conversation("1", "b", daysAgo = 90))
        val rail = recentRailAgents(conversations, directory, RailRecencyPolicy(now = now, maxAgents = 2))
        assertEquals(listOf("a" to "Alpha", "b" to "Beta"), rail)
    }

    @Test
    fun `the selected agent stays on the rail even when stale`() {
        val conversations = listOf(
            conversation("1", "a", daysAgo = 1),
            conversation("2", "d", daysAgo = 60),
        )
        val rail = recentRailAgents(conversations, directory, RailRecencyPolicy(now = now), selectedAgentId = "d")
        assertEquals(listOf("a" to "Alpha", "d" to "Delta"), rail)
    }
}
