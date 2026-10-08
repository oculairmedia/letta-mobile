@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.core.MascotShape
import com.letta.mobile.data.agents.RecentAgentsPolicy
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.rail.ShellAgentRailTags
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelActions
import com.letta.mobile.ui.shell.sidebar.ShellArchiveFilter
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** The phone drawer is the desktop's rail and agent panel (letta-mobile-c3np7.5.5). */
class ShellNavDrawerTest {
    private val now = Instant.parse("2026-10-07T12:00:00Z")

    private val input = ShellNavDrawerInput(
        agent = ShellPanelAgent(name = "Meridian", agentId = "meridian"),
        agents = listOf("meridian" to "Meridian", "lester" to "lester", "pm" to "PM - letta-mobile"),
        conversations = listOf(
            conversation("c-active", "Handoff from local-code", archived = false, at = "2026-10-07T11:56:00Z"),
            conversation("c-old", "Old thread", archived = true, at = "2026-10-01T12:00:00Z"),
        ),
        openConversationId = "c-active",
        canvases = listOf(
            CanvasDocument(id = CanvasId("k-lester"), title = "Canvas (lester)", updatedAtEpochMs = now.minusHours(23)),
            CanvasDocument(id = CanvasId("k-meridian"), title = "Canvas (Meridian)", updatedAtEpochMs = now.minusHours(13)),
        ),
        hiddenSections = setOf(LensDestination.Channels),
    )

    @Test
    fun theFocusedAgentLeavesTheRailAndHeadsThePanel() {
        val state = ShellNavDrawerMapping.state(input, now)
        assertEquals(listOf("lester", "PM - letta-mobile"), state.rail.entries.map { it.name })
        assertEquals("Meridian", state.panel.agent.name)
        assertEquals(setOf(LensDestination.Channels), state.panel.hiddenSections)
    }

    @Test
    fun theRailIsTheSharedRecentsCutWithPinsAndTheFavouriteKept() {
        val roster = (1..6).map { "a$it" to "Agent $it" }
        val recentInput = input.copy(
            agents = listOf("meridian" to "Meridian") + roster,
            agentLastActiveAt = mapOf("a3" to now - 1.hours, "a1" to now - 2.hours, "a2" to now - 30.days),
            pinnedAgentIds = setOf("a5"),
            favoriteAgentId = "a6",
            recentAgentsPolicy = RecentAgentsPolicy(maxAgents = 1),
        )
        val rail = ShellNavDrawerMapping.state(recentInput, now).rail
        // Pins and the favourite in roster order, then the newest recent; Meridian is the panel's subject.
        assertEquals(listOf("Agent 5", "Agent 6", "Agent 3"), rail.entries.map { it.name })
        assertEquals(listOf(true, false, false), rail.entries.map { it.pinned })
        assertEquals(3, rail.hiddenAgentCount)
    }

    @Test
    fun aLargeRosterWithOnlyTheFocusedAgentActiveStillFillsTheRail() {
        // The device report: ~139 agents, activity only on the focused one, gave one orb and "99+".
        val roster = (1..138).map { "a$it" to "Agent $it" }
        val bigInput = input.copy(
            agents = listOf("meridian" to "Meridian") + roster,
            agentLastActiveAt = mapOf("meridian" to now - 1.hours, "a40" to now - 20.days),
            pinnedAgentIds = setOf("meridian"),
        )
        val rail = ShellNavDrawerMapping.state(bigInput, now).rail
        // Eight orbs, newest activity first then roster order; the focused agent is neither an orb nor hidden.
        assertEquals(listOf("Agent 40") + (1..7).map { "Agent $it" }, rail.entries.map { it.name })
        assertEquals(138 - 8, rail.hiddenAgentCount)
    }

    @Test
    fun theHeroAndTheOrbsTakeEachAgentsIdentityFromTheOneMap() {
        val pm = MascotIdentity(MascotShape.TRIANGLE, argb = 0xFFF08A3C.toInt(), rotationDegrees = 135)
        val lester = MascotIdentity(MascotShape.HEXAGON, argb = 0xFF3FA0F0.toInt(), rotationDegrees = 45)
        val identities = mapOf("pm" to pm, "lester" to lester)
        val focusedPm = ShellNavDrawerMapping.state(
            input.copy(agent = ShellPanelAgent(name = "PM - letta-mobile", agentId = "pm"), identities = identities),
            now,
        )
        assertEquals(pm, focusedPm.panel.agent.identity)
        assertEquals(lester, focusedPm.rail.entries.single { it.agentId == "lester" }.identity)
        // Focus another agent and PM's orb carries the very identity its hero did.
        val focusedMeridian = ShellNavDrawerMapping.state(input.copy(identities = identities), now)
        assertEquals(pm, focusedMeridian.rail.entries.single { it.agentId == "pm" }.identity)
        assertEquals(null, focusedMeridian.panel.agent.identity)
    }

    @Test
    fun theArchiveFilterAppliesToConversations() {
        val active = ShellNavDrawerMapping.state(input, now).panel.conversations
        val archived = ShellNavDrawerMapping.state(input.copy(archiveFilter = ShellArchiveFilter.Archived), now).panel.conversations
        val all = ShellNavDrawerMapping.state(input.copy(archiveFilter = ShellArchiveFilter.All), now).panel.conversations
        assertEquals(listOf("c-active"), active.map { it.id })
        assertEquals(listOf("c-old"), archived.map { it.id })
        assertEquals(2, all.size)
        assertEquals("4m", active.single().timeLabel)
        assertEquals(true, active.single().selected)
    }

    @Test
    fun canvasesAreNewestFirstWithRelativeTimes() {
        val canvases = ShellNavDrawerMapping.state(input, now).panel.canvases
        assertEquals(listOf("Canvas (Meridian)", "Canvas (lester)"), canvases.map { it.title })
        assertEquals(listOf("13h", "23h"), canvases.map { it.timeLabel })
        val archivedOnly = ShellNavDrawerMapping.state(
            input.copy(archiveFilter = ShellArchiveFilter.Archived, archivedCanvasIds = setOf(CanvasId("k-lester"))),
            now,
        ).panel.canvases
        assertEquals(listOf("Canvas (lester)"), archivedOnly.map { it.title })
    }

    @Test
    fun theDrawerShowsTheRailBesideTheAgentPanel() = runComposeUiTest {
        val events = mutableListOf<String>()
        setContent {
            MaterialTheme {
                ShellNavDrawer(
                    state = ShellNavDrawerMapping.state(input, now),
                    actions = ShellNavDrawerActions(
                        rail = ShellAgentRailActions(onHome = { events += "home" }, onAgentSelected = { events += "agent:$it" }),
                        panel = ShellAgentPanelActions(
                            onOpenSection = { events += "section:$it" },
                            onConversationSelected = { events += "open:$it" },
                            onOpenCanvas = { events += "canvas:${it.value}" },
                        ),
                    ),
                    modifier = Modifier.width(360.dp).height(900.dp),
                )
            }
        }
        listOf("Meridian", "Memory", "Schedules", "Skills", "New chat", "PINNED", "CANVASES").forEach { onNodeWithText(it).assertExists() }
        onNodeWithText("Channels").assertDoesNotExist()
        onNodeWithTag(ShellAgentRailTags.orb("Meridian")).assertDoesNotExist()

        onNodeWithContentDescription("Home").performClick()
        onNodeWithTag(ShellAgentRailTags.orb("lester"), useUnmergedTree = true).performClick()
        onNodeWithText("Schedules").performClick()
        onNodeWithText("Handoff from local-code").performClick()
        onNodeWithText("Canvas (Meridian)").performClick()
        assertEquals(listOf("home", "agent:lester", "section:Schedules", "open:c-active", "canvas:k-meridian"), events)
    }

    private fun conversation(id: String, summary: String, archived: Boolean, at: String) = Conversation(
        id = ConversationId(id),
        agentId = AgentId("meridian"),
        summary = summary,
        lastMessageAt = at,
        archived = archived,
    )

    private fun Instant.minusHours(hours: Int): Long = toEpochMilliseconds() - hours * 3_600_000L
}
