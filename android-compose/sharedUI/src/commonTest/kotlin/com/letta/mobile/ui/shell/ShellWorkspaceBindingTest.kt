package com.letta.mobile.ui.shell

import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.ShellChatPort
import com.letta.mobile.data.shell.ShellConversationRef
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.data.shell.ShellWorkspaceState
import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** letta-mobile-ckmub.17.3: the shared navigation drawer over the shared workspace (ported from the web's WebShellStateTest). */
class ShellWorkspaceBindingTest {
    private val now = Instant.parse("2026-10-11T12:00:00Z")
    private val roster = listOf("agent-1" to "Nora", "agent-2" to "Ada", "agent-3" to "Tom")

    @Test
    fun theFocusedAgentHeadsThePanelAndTheRestAreOnTheRail() {
        val workspace = ShellWorkspaceState(ShellDestination.Chat())
        workspace.focusedAgentId = AgentId("agent-1")
        workspace.rosterChanged(roster.map { AgentId(it.first) })

        val drawer = workspace.navDrawerState(roster, now)

        assertEquals("Nora", drawer.panel.agent.name)
        assertEquals(listOf("Ada", "Tom"), drawer.rail.entries.map { it.name })
        assertTrue(drawer.panel.hiddenSections.isEmpty(), "every section shows")
        assertEquals(LensDestination.Conversations, drawer.panel.selectedSection)
    }

    @Test
    fun railAndPanelNavigateTheWorkspaceAndCloseTheDrawer() {
        val workspace = ShellWorkspaceState(ShellDestination.Chat())
        workspace.rosterChanged(roster.map { AgentId(it.first) })
        var closed = 0
        val actions = ShellWorkspaceNavigator(workspace, NoChat).navDrawerActions(afterNavigate = { closed += 1 })

        actions.panel.onOpenSettings()
        assertEquals(ShellDestination.Settings(), workspace.destination)
        assertTrue(workspace.navDrawerState(roster, now).panel.settingsSelected)

        actions.rail.onHome()
        assertTrue(workspace.navDrawerState(roster, now).rail.homeSelected)

        actions.rail.onAgentSelected("agent-2")
        assertEquals(ShellDestination.Chat("agent-2"), workspace.destination)

        actions.panel.onOpenSection(LensDestination.Memory)
        assertEquals(ShellDestination.Memory("agent-2"), workspace.destination)
        assertEquals(4, closed)
    }

    private fun recreated(state: ShellWorkspaceState): ShellWorkspaceState {
        val saved = with(ShellWorkspaceSaver) { SaverScope { true }.save(state) }
        return requireNotNull(ShellWorkspaceSaver.restore(requireNotNull(saved)))
    }

    @Test
    fun aRecreationKeepsTheAgentAndConversationTheUserIsIn() {
        val workspace = ShellWorkspaceState(ShellDestination.Chat())
        workspace.rosterChanged(roster.map { AgentId(it.first) })
        workspace.navigate(ShellDestination.Chat("agent-3"))
        val request = workspace.requestToken()
        assertTrue(workspace.conversationShown(request, ConversationId("conv-9"), AgentId("agent-3")))

        val restored = recreated(workspace)
        restored.rosterChanged(roster.map { AgentId(it.first) })

        assertEquals(AgentId("agent-3"), restored.focusedAgentId, "rotation must not fall back to the roster's first agent")
        assertEquals(ConversationId("conv-9"), restored.selectedConversationId)
        assertEquals(ShellDestination.Chat("agent-3", "conv-9"), restored.destination)
    }

    @Test
    fun aRecreationKeepsTheRailWidthAndAnOtherPage() {
        val workspace = ShellWorkspaceState(ShellDestination.Home)
        workspace.railExpanded = true
        workspace.navigate(ShellDestination.Memory("agent-2"))

        val restored = recreated(workspace)

        assertTrue(restored.railExpanded)
        assertEquals(ShellDestination.Memory("agent-2"), restored.destination)
    }

    @Test
    fun aRecreationOfAFreshWorkspaceStaysOnHome() {
        val restored = recreated(ShellWorkspaceState(ShellDestination.Home))
        assertEquals(ShellDestination.Home, restored.destination)
        assertFalse(restored.railExpanded)
    }

    @Test
    fun noRosterReadsAsLetta() {
        val workspace = ShellWorkspaceState(ShellDestination.Chat())
        workspace.rosterChanged(emptyList())
        val drawer = workspace.navDrawerState(emptyList(), now)
        assertEquals("Letta", drawer.panel.agent.name)
        assertFalse(drawer.panel.home)
    }

    @Test
    fun missingRosterKeepsTheRequestedAgentInThePanel() {
        val workspace = ShellWorkspaceState(ShellDestination.Chat("agent-3"))
        val drawer = workspace.navDrawerState(emptyList(), now)

        assertEquals("agent-3", drawer.panel.agent.agentId)
        assertEquals("Letta", drawer.panel.agent.name)
    }

    private object NoChat : ShellChatPort {
        override fun conversations(): List<ShellConversationRef> = emptyList()
        override val selectedConversation: ConversationId? = null
        override fun selectConversation(conversation: ConversationId) = Unit
        override fun createConversationForAgent(agent: AgentId, onCreated: (ConversationId) -> Unit) = Unit
        override fun startNewChat(agent: AgentId?) = Unit
        override fun send(conversation: ConversationId, text: String) = Unit
        override fun stageComposerText(text: String) = Unit
    }
}
