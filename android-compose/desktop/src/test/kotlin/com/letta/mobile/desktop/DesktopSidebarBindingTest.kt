package com.letta.mobile.desktop

import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.desktop.chat.ConversationArchiveFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-ckmub.3.10: the desktop binds the shared agent panel; only routing is the desktop's own. */
class DesktopSidebarBindingTest {

    @Test
    fun theOpenDestinationBecomesTheSelectedSection() {
        assertEquals(LensDestination.Skills, page(DesktopDestination.Agents).selectedSection)
        assertEquals(LensDestination.Memory, page(DesktopDestination.Memory).selectedSection)
        assertNull(page(DesktopDestination.Settings).selectedSection)
        assertTrue(page(DesktopDestination.Settings).settingsSelected)
        assertTrue(page(DesktopDestination.Home).home)
        assertFalse(page(DesktopDestination.Memory).home)
    }

    /** letta-mobile-zxk2n.32: Skills lands on the destination that draws the shared Skills page. */
    @Test
    fun skillsOpensTheSkillsPageDestination() {
        WorkPlayMode.entries.forEach { mode ->
            assertEquals(DesktopDestination.Agents, lensNavTarget(mode, LensDestination.Skills).first)
        }
    }

    @Test
    fun aConversationIsOpenOnlyWhileTheConversationPageShows() {
        assertEquals("conv-1", desktopOpenConversation(DesktopDestination.Conversations, "conv-1"))
        assertNull(desktopOpenConversation(DesktopDestination.Memory, "conv-1"))
    }

    @Test
    fun sidebarSelectionFollowsTheOwnerWhileControllerStillShowsAnOlderChat() {
        val workspace = com.letta.mobile.data.shell.ShellWorkspaceState()
        workspace.bindSource("test-source", 1)
        workspace.selectFocus(com.letta.mobile.data.shell.CurrentFocus("chat",
            com.letta.mobile.data.model.AgentId("agent-0"), com.letta.mobile.data.model.ConversationId("new-chat"),
            com.letta.mobile.data.shell.CurrentFocusChatMode.Exact))
        val rows = listOf("old-chat", "new-chat").map { id ->
            com.letta.mobile.desktop.chat.DesktopConversationSummary(id, id, "Agent", "Just now", "", agentId = "agent-0")
        }
        val chat = com.letta.mobile.desktop.chat.initialLiveDesktopChatSurfaceState(defaultDesktopBootstrapState())
            .copy(conversations = rows, selectedConversationId = "old-chat", isLoading = false)
        val focus = DesktopAgentFocus(emptyList(), emptyList(), emptyMap(), "agent-0", "Agent", 0, emptyMap(), emptyMap())
        val activity = DesktopShellActivity(null, null, false, false, emptySet())
        val libraries = DesktopLibraryStates(com.letta.mobile.data.memory.graph.MemoryPageState(),
            com.letta.mobile.data.schedules.ScheduleLibraryState(), com.letta.mobile.data.channel.ChannelsPageState(),
            com.letta.mobile.data.tools.ToolLibraryState())
        val lists = DesktopShellLists(emptyList(), ConversationArchiveFilter.Active, rows, mentionables = emptyList(),
            search = com.letta.mobile.ui.shell.header.UnifiedSearchSources(), subagentsWithHistory = emptyList())
        val frame = DesktopShellFrame(chat, focus, activity, libraries, lists)
        val inputs = DesktopSidebarInputs(DesktopSidebarMarks(), emptyList(), emptySet(), null, null)

        val panel = desktopSidebarPanelState(workspace, frame, inputs)

        assertEquals(listOf("new-chat"), panel.conversations.filter { it.selected }.map { it.id })
    }

    @Test
    fun theArchiveFilterRoundTrips() {
        ConversationArchiveFilter.entries.forEach { assertEquals(it, it.toShellArchiveFilter().toConversationArchiveFilter()) }
    }

    private fun page(destination: DesktopDestination) = desktopPanelPage(destination, WorkPlayMode.Work)
}
