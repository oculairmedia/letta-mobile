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
