package com.letta.mobile.data.shell

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-ckmub.17.3: the one shell state desktop and web bind, and its navigation verbs. */
class ShellWorkspaceStateTest {
    private val a1 = AgentId("agent-1")
    private val a2 = AgentId("agent-2")
    private val a3 = AgentId("agent-3")
    private val cOld = ConversationId("c-old")
    private val cNew = ConversationId("c-new")
    private val cOther = ConversationId("c-other")
    private val older = ShellConversationRef(cOld, a1, "2026-10-01T10:00:00Z")
    private val newer = ShellConversationRef(cNew, a1, "2026-10-02T10:00:00Z")
    private val other = ShellConversationRef(cOther, a2, "2026-10-03T10:00:00Z")

    @Test
    fun selectingAnAgentGoesToItsLastActiveThenItsLatestThenANewConversation() {
        val chat = FakeChat(listOf(older, newer, other))
        val navigator = ShellWorkspaceNavigator(ShellWorkspaceState(), chat)

        navigator.openAgent(a1)
        assertEquals(listOf(cNew), chat.selected)

        navigator.state.conversationShown(cOld, a1)
        navigator.openAgent(a2)
        navigator.openAgent(a1)
        assertEquals(listOf(cNew, cOther, cOld), chat.selected)

        // The last active conversation is gone: back to the latest.
        chat.all = listOf(newer, other)
        navigator.state.conversationsReported(setOf(cNew, cOther))
        navigator.openAgent(a1)
        assertEquals(cNew, chat.selected.last())

        navigator.openAgent(a3)
        assertEquals(listOf(a3), chat.created)
        assertEquals(a3, navigator.state.focusedAgentId)
        assertEquals(a3.value, navigator.state.destination.agentId)
    }

    @Test
    fun shownConversationsOpenTabsAndClosingTheActiveOneFallsBack() {
        val chat = FakeChat(listOf(older, newer, other))
        val navigator = ShellWorkspaceNavigator(ShellWorkspaceState(ShellDestination.Chat()), chat)
        val state = navigator.state
        for (id in listOf(cOld, cNew, cOther)) state.conversationShown(id, null)
        assertEquals(listOf("c-old", "c-new", "c-other"), state.tabs.openConversationIds)

        navigator.reorderConversationTab(cOther, 0)
        assertEquals(listOf("c-other", "c-old", "c-new"), state.tabs.openConversationIds)

        // Not the active tab: only the strip changes.
        chat.selectedConversation = cNew
        navigator.closeConversationTab(cOther)
        assertEquals(listOf("c-old", "c-new"), state.tabs.openConversationIds)
        assertTrue(chat.selected.isEmpty())

        // The active tab: the one beside it opens; the last one leaves Home.
        navigator.closeConversationTab(cNew)
        assertEquals(listOf(cOld), chat.selected)
        chat.selectedConversation = cOld
        navigator.closeConversationTab(cOld)
        assertEquals(ShellDestination.Home, state.page)
        assertTrue(state.tabs.openConversationIds.isEmpty())
    }

    @Test
    fun aConversationGetsATabOnlyWhileTheChatIsThePage() {
        val state = ShellWorkspaceState(ShellDestination.Memory())
        state.conversationShown(cOld, a1)
        assertTrue(state.tabs.openConversationIds.isEmpty())
        state.showChat()
        state.conversationShown(cOld, a1)
        assertEquals(listOf("c-old"), state.tabs.openConversationIds)

        state.conversationsReported(setOf(cNew))
        assertEquals(listOf("c-old"), state.tabs.openConversationIds, "the selected conversation keeps its tab")
    }

    @Test
    fun theRouteRoundTripsAndCarriesTheFocus() {
        val state = ShellWorkspaceState(ShellDestination.Chat())
        state.rosterChanged(listOf(a1, a2))
        state.focusedAgentId = a2
        assertEquals("chat?agent=agent-2", state.route)

        val reopened = ShellWorkspaceState(ShellDestination.Chat())
        assertTrue(reopened.openRoute(state.route))
        reopened.rosterChanged(listOf(a1, a2))
        assertEquals(a2, reopened.focusedAgentId)
        assertEquals(state.route, reopened.route)

        assertTrue(reopened.openRoute("skills?section=tools"))
        assertEquals(ShellDestination.Skills("agent-2", ShellSkillsSection.Tools), reopened.destination)
        assertTrue(reopened.openRoute("agent-editor?agent=agent-1"))
        assertEquals(ShellDestination.AgentEditor("agent-1"), reopened.destination)
        assertEquals(ShellDestination.Chat("agent-1"), reopened.pageFor(a1, null))

        assertFalse(reopened.openRoute("nowhere"))
        assertFalse(reopened.openRoute(""))
        assertEquals(ShellDestination.AgentEditor("agent-1"), reopened.destination)
    }

    @Test
    fun navigatingLeavesTheEditorAndShowingAPageKeepsIt() {
        val state = ShellWorkspaceState()
        state.openNewAgent()
        assertEquals(ShellDestination.AgentEditor(), state.destination)
        assertNull(state.editAgentId)

        state.showPage(ShellDestination.Chat())
        assertEquals(ShellDestination.AgentEditor(), state.agentEditor)

        state.navigate(ShellDestination.Settings(ShellSettingsSection.Pairing))
        assertNull(state.agentEditor)
        assertEquals(ShellDestination.Settings(ShellSettingsSection.Pairing), state.destination)

        // The same kind of page stays as it is, its anchor too.
        state.showPage(ShellDestination.Settings())
        assertEquals(ShellDestination.Settings(ShellSettingsSection.Pairing), state.page)
    }

    @Test
    fun aRosterRefreshKeepsTheFocusWhileTheAgentIsThere() {
        val state = ShellWorkspaceState(ShellDestination.Chat())
        state.rosterChanged(listOf(a1, a2))
        assertEquals(a1, state.focusedAgentId)
        state.focusedAgentId = a2
        state.rosterChanged(listOf(a1, a2, a3))
        assertEquals(a2, state.focusedAgentId)
        state.rosterChanged(listOf(a3))
        assertEquals(a3, state.focusedAgentId)
        state.rosterChanged(emptyList())
        assertNull(state.focusedAgentId)

        val stranger = AgentId("agent-9")
        assertEquals(stranger, state.focusFor(stranger, listOf(a3)))
        state.focusedAgentId = a3
        assertEquals(a3, state.focusFor(null, listOf(a1, a3)))
        assertEquals(a1, state.focusFor(null, listOf(a1)))
    }

    @Test
    fun aBackendSwitchClearsTheStaleFocusTabsAndEditor() {
        val state = ShellWorkspaceState(ShellDestination.Memory("agent-1"))
        state.sessionChanged("session-1")
        state.showChat()
        state.conversationShown(cOld, a1)
        state.navigate(ShellDestination.Memory("agent-1"))
        state.editAgentId = a1
        state.sessionChanged("session-1")
        assertEquals(a1, state.focusedAgentId, "the same session keeps everything")

        state.sessionChanged("session-2")
        assertNull(state.focusedAgentId)
        assertNull(state.selectedConversationId)
        assertNull(state.agentEditor)
        assertTrue(state.tabs.openConversationIds.isEmpty())
        assertEquals(ShellDestination.Memory(), state.destination)
        assertNull(state.resolveAgentChat(a1, listOf(other)))
    }

    @Test
    fun homePromptsGoToTheFocusedAgentsNewestThenTheFleetsThenANewChat() {
        val chat = FakeChat(listOf(older, newer, other))
        val navigator = ShellWorkspaceNavigator(ShellWorkspaceState(), chat)

        navigator.submitHomePrompt(ShellHomePrompt("  hi  ", focusedAgent = a1, rosterAgent = null))
        navigator.submitHomePrompt(ShellHomePrompt("yo", focusedAgent = null, rosterAgent = null))
        assertEquals(listOf(cNew to "hi", cOther to "yo"), chat.sent)
        assertEquals(ShellDestination.Chat(), navigator.state.page)

        chat.all = emptyList()
        val roster = AgentId("agent-5")
        navigator.submitHomePrompt(ShellHomePrompt("new", focusedAgent = null, rosterAgent = roster))
        assertEquals(listOf(roster), chat.created)
        assertEquals(ConversationId("created-agent-5") to "new", chat.sent.last())
        navigator.submitHomePrompt(ShellHomePrompt("staged", focusedAgent = null, rosterAgent = null))
        assertEquals("staged", chat.staged)
        navigator.submitHomePrompt(ShellHomePrompt("   ", focusedAgent = null, rosterAgent = null))
        assertEquals(3, chat.sent.size)
    }

    private class FakeChat(var all: List<ShellConversationRef>) : ShellChatPort {
        override var selectedConversation: ConversationId? = null
        val selected = mutableListOf<ConversationId>()
        val created = mutableListOf<AgentId>()
        val sent = mutableListOf<Pair<ConversationId, String>>()
        var staged: String? = null

        override fun conversations(): List<ShellConversationRef> = all

        override fun selectConversation(conversation: ConversationId) {
            selected += conversation
        }

        override fun createConversationForAgent(agent: AgentId, onCreated: (ConversationId) -> Unit) {
            created += agent
            onCreated(ConversationId("created-${agent.value}"))
        }

        override fun startNewChat(agent: AgentId?) {
            agent?.let(created::add)
        }

        override fun send(conversation: ConversationId, text: String) {
            sent += conversation to text
        }

        override fun stageComposerText(text: String) {
            staged = text
        }
    }
}
