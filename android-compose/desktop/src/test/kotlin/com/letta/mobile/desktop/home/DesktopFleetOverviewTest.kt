package com.letta.mobile.desktop.home

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopFleetOverviewTest {

    private val now: Instant = Instant.parse("2026-07-26T12:00:00Z")

    private fun agent(id: String, name: String, model: String? = null) = Agent(
        id = AgentId(id),
        name = name,
        model = model,
    )

    private fun conversation(
        id: String,
        agentId: String,
        agentName: String = agentId,
        updatedAt: String,
        preview: String = "",
    ) = DesktopConversationSummary(
        id = id,
        title = id,
        agentName = agentName,
        updatedAtLabel = updatedAt,
        lastMessagePreview = preview,
        agentId = agentId,
    )

    private fun overview(
        conversations: List<DesktopConversationSummary>,
        agents: List<Agent>,
        running: Set<String> = emptySet(),
    ) = buildFleetOverview(
        FleetOverviewParams(
            conversations = conversations,
            rosterAgents = agents,
            runningAgentIds = running,
            now = now,
            zone = ZoneOffset.UTC,
            days = 7,
            hours = 6,
        ),
    )

    @Test
    fun `roster-only agents still get a row`() {
        val result = overview(
            conversations = emptyList(),
            agents = listOf(agent("a-1", "Scout", "openai/gpt-5")),
        )
        val row = result.agents.single()
        assertEquals("Scout", row.name)
        assertEquals("openai/gpt-5", row.model)
        assertEquals(0, row.conversationCount)
        assertNull(row.lastActivity)
        assertEquals(1, result.summary.totalAgents)
        assertEquals(0, result.summary.activeToday)
    }

    @Test
    fun `conversation-only agents are merged into the fleet`() {
        val result = overview(
            conversations = listOf(
                conversation("c-1", "a-2", "Ops", "2026-07-26T10:00:00Z"),
                conversation("c-2", "a-2", "Ops", "2026-07-25T10:00:00Z"),
            ),
            agents = listOf(agent("a-1", "Scout")),
        )
        assertEquals(2, result.agents.size)
        val ops = result.agents.single { it.agentId == "a-2" }
        assertEquals("Ops", ops.name)
        assertEquals(2, ops.conversationCount)
        assertEquals(Instant.parse("2026-07-26T10:00:00Z"), ops.lastActivity)
        assertEquals(2, result.summary.totalConversations)
        assertEquals(1, result.summary.activeToday)
    }

    @Test
    fun `activity buckets are oldest-first and windowed`() {
        val result = overview(
            conversations = listOf(
                conversation("c-1", "a-1", updatedAt = "2026-07-26T09:00:00Z"), // today
                conversation("c-2", "a-1", updatedAt = "2026-07-26T11:00:00Z"), // today
                conversation("c-3", "a-1", updatedAt = "2026-07-24T09:00:00Z"), // 2 days ago
                conversation("c-4", "a-1", updatedAt = "2026-01-01T09:00:00Z"), // outside window
                conversation("c-5", "a-1", updatedAt = "Queued"), // unparseable sentinel
            ),
            agents = listOf(agent("a-1", "Scout")),
        )
        val buckets = result.agents.single().activityByDay
        assertEquals(7, buckets.size)
        assertEquals(2, buckets[6], "today is the last bucket")
        assertEquals(1, buckets[4], "two days ago")
        assertEquals(0, buckets[0])
        assertEquals(3, buckets.sum(), "out-of-window and unparseable rows are excluded")
        assertEquals(buckets, result.summary.conversationsByDay)
        assertEquals(5, result.summary.totalConversations)
    }

    @Test
    fun `running agents are counted from the running id set`() {
        val result = overview(
            conversations = emptyList(),
            agents = listOf(agent("a-1", "Scout"), agent("a-2", "Ops")),
            running = setOf("a-2"),
        )
        assertEquals(1, result.summary.runningNow)
        assertTrue(result.agents.single { it.agentId == "a-2" }.running)
    }

    @Test
    fun `hourly buckets are oldest-first and windowed to the last hours`() {
        val result = overview(
            conversations = listOf(
                conversation("c-1", "a-1", updatedAt = "2026-07-26T11:30:00Z"), // this hour
                conversation("c-2", "a-1", updatedAt = "2026-07-26T09:10:00Z"), // 2h ago
                conversation("c-3", "a-1", updatedAt = "2026-07-26T09:50:00Z"), // 2h ago
                conversation("c-4", "a-1", updatedAt = "2026-07-25T09:00:00Z"), // outside 6h
                conversation("c-5", "a-1", updatedAt = "Queued"), // unparseable sentinel
            ),
            agents = listOf(agent("a-1", "Scout")),
        )
        val hours = result.agents.single().activityByHour
        assertEquals(6, hours.size)
        assertEquals(1, hours[5], "the current hour is the last bucket")
        assertEquals(2, hours[3], "two hours ago")
        assertEquals(0, hours[0])
        assertEquals(3, hours.sum(), "out-of-window and unparseable rows are excluded")
    }

    @Test
    fun `recent conversations are fleet-wide and newest first`() {
        val result = overview(
            conversations = listOf(
                conversation("old", "a-1", updatedAt = "2026-07-20T10:00:00Z"),
                conversation("newest", "a-2", "Ops", "2026-07-26T11:00:00Z", preview = " hi "),
                conversation("mid", "a-1", updatedAt = "2026-07-26T08:00:00Z"),
            ),
            agents = listOf(agent("a-1", "Scout"), agent("a-2", "Ops")),
        )
        assertEquals(listOf("newest", "mid", "old"), result.recent.map { it.conversationId })
        val top = result.recent.first()
        assertEquals("Ops", top.agentName)
        assertEquals("hi", top.preview)
        assertEquals(Instant.parse("2026-07-26T11:00:00Z"), top.updatedAt)
    }

    /**
     * Home keys its LazyColumn by conversationId, and Compose throws "Key already used" on a
     * duplicate — which would crash the DEFAULT destination. The conversation endpoint does
     * repeat rows during an active run, so this pins the guarantee that Home never receives
     * a duplicate key.
     *
     * buildFleetOverview already dedups by id up front, so this test documents that contract
     * rather than adding a second one. It keeps the FIRST occurrence, so the surviving row can
     * carry a staler timestamp and preview than the newest copy — cosmetic, and asserted below
     * so a change to that rule is a deliberate decision rather than a silent one.
     */
    @Test
    fun `duplicate conversation rows are collapsed to one`() {
        val result = overview(
            conversations = listOf(
                conversation("dupe", "a-1", updatedAt = "2026-07-26T08:00:00Z"),
                conversation("other", "a-1", updatedAt = "2026-07-26T09:00:00Z"),
                conversation("dupe", "a-1", updatedAt = "2026-07-26T10:00:00Z"),
            ),
            agents = listOf(agent("a-1", "Scout")),
        )
        val ids = result.recent.map { it.conversationId }
        assertEquals(ids.size, ids.toSet().size, "Home would crash on a duplicate LazyColumn key")
        assertEquals(listOf("other", "dupe"), ids)
        assertEquals(
            Instant.parse("2026-07-26T08:00:00Z"),
            result.recent.first { it.conversationId == "dupe" }.updatedAt,
            "dedup keeps the first occurrence, not the freshest",
        )
    }

    @Test
    fun `recent conversations prefer the resolved roster name over a raw id`() {
        val result = overview(
            conversations = listOf(
                conversation("c-1", "agent-abc", agentName = "agent-abc", updatedAt = "2026-07-26T11:00:00Z"),
            ),
            agents = listOf(agent("agent-abc", "Meridian")),
        )
        assertEquals("Meridian", result.recent.single().agentName)
    }

    @Test
    fun `an unresolved agent name is flagged as an id fallback`() {
        val result = overview(
            conversations = listOf(
                conversation("c-1", "agent-abc", agentName = "agent-abc", updatedAt = "2026-07-26T11:00:00Z"),
            ),
            agents = emptyList(),
        )
        assertTrue(result.agents.single().nameIsIdFallback)
    }

    @Test
    fun `the composer target prefers the focused agent's newest conversation`() {
        val conversations = listOf(
            conversation("a1-old", "a-1", updatedAt = "2026-07-20T10:00:00Z"),
            conversation("a1-new", "a-1", updatedAt = "2026-07-26T08:00:00Z"),
            conversation("a2-newest", "a-2", updatedAt = "2026-07-26T11:00:00Z"),
        )
        assertEquals("a1-new", preferredComposerConversationId(conversations, "a-1"))
        assertEquals("a2-newest", preferredComposerConversationId(conversations, null))
        assertEquals(
            "a2-newest",
            preferredComposerConversationId(conversations, "unknown-agent"),
            "an agent with no conversations falls back to the fleet's newest",
        )
        assertNull(preferredComposerConversationId(emptyList(), "a-1"))
    }

    private fun stat(name: String, count: Int, last: Instant?) = FleetAgentStat(
        agentId = name,
        name = name,
        model = null,
        conversationCount = count,
        lastActivity = last,
        running = false,
        activityByDay = emptyList(),
    )

    @Test
    fun `sorting by name respects direction`() {
        val rows = listOf(stat("Zed", 1, null), stat("alice", 9, null), stat("Mid", 5, null))
        assertEquals(
            listOf("alice", "Mid", "Zed"),
            sortFleet(rows, FleetSort(FleetSortKey.Agent, descending = false)).map { it.name },
        )
        assertEquals(
            listOf("Zed", "Mid", "alice"),
            sortFleet(rows, FleetSort(FleetSortKey.Agent, descending = true)).map { it.name },
        )
    }

    @Test
    fun `agents without activity sort last in both directions`() {
        val rows = listOf(
            stat("old", 1, Instant.parse("2026-07-20T00:00:00Z")),
            stat("never", 0, null),
            stat("new", 1, Instant.parse("2026-07-26T00:00:00Z")),
        )
        assertEquals(
            listOf("new", "old", "never"),
            sortFleet(rows, FleetSort(FleetSortKey.LastActivity, descending = true)).map { it.name },
        )
        assertEquals(
            listOf("old", "new", "never"),
            sortFleet(rows, FleetSort(FleetSortKey.LastActivity, descending = false)).map { it.name },
        )
    }

    @Test
    fun `conversation sort is descending with a stable name tie-break`() {
        val rows = listOf(stat("b", 3, null), stat("a", 3, null), stat("c", 8, null))
        assertEquals(
            listOf("c", "b", "a"),
            sortFleet(rows, FleetSort(FleetSortKey.Conversations, descending = true)).map { it.name },
        )
    }

    @Test
    fun `header clicks flip the active column and adopt natural direction otherwise`() {
        val start = FleetSort(FleetSortKey.LastActivity, descending = true)
        assertEquals(
            FleetSort(FleetSortKey.LastActivity, descending = false),
            start.toggled(FleetSortKey.LastActivity),
        )
        assertEquals(FleetSort(FleetSortKey.Agent, descending = false), start.toggled(FleetSortKey.Agent))
        assertEquals(
            FleetSort(FleetSortKey.Conversations, descending = true),
            start.toggled(FleetSortKey.Conversations),
        )
    }
}
