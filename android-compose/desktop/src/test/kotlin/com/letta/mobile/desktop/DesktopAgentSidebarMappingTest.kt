package com.letta.mobile.desktop

import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.desktop.chat.ConversationArchiveFilter
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import com.letta.mobile.ui.shell.sidebar.ShellArchiveFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The desktop sidebar feeds the shared agent panel (letta-mobile-c3np7.2.3) without changing what it shows. */
class DesktopAgentSidebarMappingTest {

    @Test
    fun theOpenDestinationBecomesTheSelectedSection() {
        assertEquals(LensDestination.Skills, state(DesktopDestination.Agents).toShellPanelState().selectedSection)
        assertEquals(LensDestination.Memory, state(DesktopDestination.Memory).toShellPanelState().selectedSection)
        assertNull(state(DesktopDestination.Settings).toShellPanelState().selectedSection)
        assertTrue(state(DesktopDestination.Settings).toShellPanelState().settingsSelected)
        assertTrue(state(DesktopDestination.Home).toShellPanelState().home)
    }

    @Test
    fun aConversationIsSelectedOnlyWhileTheConversationPageShows() {
        val onChat = state(DesktopDestination.Conversations).toShellPanelState().conversations.single()
        val onMemory = state(DesktopDestination.Memory).toShellPanelState().conversations.single()
        assertTrue(onChat.selected)
        assertFalse(onMemory.selected)
        assertTrue(onChat.thinking)
        assertEquals("Shipping notes", onChat.title)
        assertEquals("", onChat.preview)
    }

    @Test
    fun canvasesCarryTheirArchiveAndActiveMarks() {
        val canvases = state(DesktopDestination.Home).toShellPanelState().canvases
        assertEquals(listOf(true, false), canvases.map { it.selected })
        assertEquals(listOf(false, true), canvases.map { it.archived })
    }

    @Test
    fun sectionsAndFiltersRouteBackToDesktopDestinations() {
        val opened = mutableListOf<DesktopDestination>()
        val filters = mutableListOf<ConversationArchiveFilter>()
        val actions = DesktopAgentSidebarActions(
            onArchiveFilterChange = { filters += it },
            onArchiveConversation = { _, _ -> },
            onModeChange = {},
            onDestinationSelected = { opened += it },
            onConversationSelected = {},
            onDeleteConversation = {},
            onNewChat = {},
            onEditAgent = {},
        ).toShellPanelActions(WorkPlayMode.Work)

        actions.onOpenSection(LensDestination.Skills)
        actions.onOpenSettings()
        actions.onArchiveFilterChange(ShellArchiveFilter.Archived)

        assertEquals(listOf(DesktopDestination.Agents, DesktopDestination.Settings), opened)
        assertEquals(listOf(ConversationArchiveFilter.Archived), filters)
        ConversationArchiveFilter.entries.forEach { assertEquals(it, it.toShellArchiveFilter().toConversationArchiveFilter()) }
    }

    private fun state(destination: DesktopDestination) = DesktopAgentSidebarState(
        agentName = "Meridian",
        agentOrbIndex = 2,
        agentId = "agent-1",
        conversations = listOf(
            DesktopConversationSummary(
                id = "conv-1",
                title = "Shipping notes",
                agentName = "Meridian",
                updatedAtLabel = "not-an-instant",
                lastMessagePreview = "Loaded from backend",
            ),
        ),
        selectedConversationId = "conv-1",
        thinkingConversationId = "conv-1",
        archiveFilter = ConversationArchiveFilter.Active,
        selectedDestination = destination,
        mode = WorkPlayMode.Work,
        canvases = listOf(CanvasDocument(id = CanvasId("a"), title = "Roadmap"), CanvasDocument(id = CanvasId("b"), title = "Old")),
        activeCanvasId = CanvasId("a"),
        archivedCanvasIds = setOf(CanvasId("b")),
    )
}
