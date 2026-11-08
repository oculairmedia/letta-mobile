package com.letta.mobile.ui.navigation

import com.letta.mobile.NotificationNavigationTarget
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.LastChatSelection
import com.letta.mobile.data.shell.CurrentFocus
import com.letta.mobile.data.shell.CurrentFocusChatMode
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.data.shell.ShellWorkspaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag
import com.letta.mobile.data.shell.FocusAvailability

/** How the shared shell's verbs drive Android's chat page (letta-mobile-ckmub.2). */
@Tag("unit")
class AndroidShellChatPortTest {
    private val agent = AgentId("agent-1")
    private val fleet = mutableListOf(
        Conversation(id = ConversationId("c-old"), agentId = agent, lastMessageAt = "2026-10-01T10:00:00Z"),
        Conversation(id = ConversationId("c-new"), agentId = agent, lastMessageAt = "2026-10-20T10:00:00Z"),
    )
    private val workspace = ShellWorkspaceState(ShellDestination.Home)
    private val port = AndroidShellChatPort(workspace, { fleet }, { listOf(agent) }, clock = { 7L })
    private val navigator = ShellWorkspaceNavigator(workspace, port)

    @Test
    fun openingAnAgentShowsItsNewestConversation() {
        navigator.openAgent(agent)
        assertEquals(ConversationId("c-new"), port.opening?.conversation)
        assertEquals(ConversationId("c-new"), workspace.selectedConversationId)
        assertTrue(workspace.page is ShellDestination.Chat)
    }

    @Test
    fun aNewChatKeepsItsPageOnceItsConversationExists() {
        var created: ConversationId? = null
        assertTrue(workspace.selectFocus(CurrentFocus("chat", agent, chatMode = CurrentFocusChatMode.New)))
        val request = workspace.requestToken()
        port.createConversationForAgent(agent) {
            created = it
            assertTrue(workspace.conversationShown(request, it, agent))
        }
        val opening = checkNotNull(port.opening)
        assertNull(opening.conversation)
        assertEquals(7L, opening.fresh)

        port.conversationShown(opening, ConversationId("c-made"))

        assertEquals(ConversationId("c-made"), created)
        assertEquals(ConversationId("c-made"), workspace.selectedConversationId)
        // The page for the now-selected conversation is still the new chat's, so its view model stays.
        assertSame(opening, port.openingFor(agent, ConversationId("c-made")))
    }

    @Test
    fun homesChatboxSendsToTheNewestConversationOnceItLoads() {
        navigator.submitHomePrompt(com.letta.mobile.data.shell.ShellHomePrompt("hello", focusedAgent = agent, rosterAgent = agent))
        val opening = checkNotNull(port.opening)
        assertEquals(ConversationId("c-new"), opening.conversation)
        assertEquals("hello", opening.firstMessage)
    }

    @Test
    fun aShareWaitsForItsAgentAndStartsANewChatWithIt() {
        assertEquals(false, port.deliverShare(agent))
        port.pendingShare = "look at this"
        assertTrue(port.deliverShare(agent))
        val opening = checkNotNull(port.opening)
        assertNull(opening.conversation)
        assertEquals("look at this", opening.firstMessage)
        assertNull(port.pendingShare)
    }

    @Test
    fun aDraftWaitsForTheComposer() {
        port.stageComposerText("draft")
        assertEquals("draft", port.draft.value)
    }

    @Test
    fun theStartPageRequiresConfigAndNeverUsesUnscopedHistoryOrPreferences() {
        val notification = NotificationNavigationTarget("agent-n", "conv-n")
        val last = LastChatSelection(agentId = "agent-l", conversationId = "conv-l")
        assertEquals(ShellDestination.Settings(), resolveStartDestination(false, notification, last, "agent-f"))
        assertEquals(ShellDestination.Home, resolveStartDestination(true, notification, last, "agent-f"))
        assertEquals(ShellDestination.Home, resolveStartDestination(true, null, last, "agent-f"))
        assertEquals(ShellDestination.Home, resolveStartDestination(true, null, null, "agent-f"))
        assertEquals(ShellDestination.Home, resolveStartDestination(true, null, null, null))
    }
    @Test
    fun lateCreationCannotReplaceAnotherAgentsExactFocusOrSend() {
        workspace.bindSource("backend-a", 1L)
        navigator.openNewChat(agent)
        var callbacks = 0
        port.createConversationForAgent(agent) { callbacks++ }
        val openingA = checkNotNull(port.opening)
        val agentB = AgentId("agent-b")
        port.open(agentB, ConversationId("b-exact"))
        val openingB = port.opening

        port.conversationShown(openingA, ConversationId("a-created"))

        assertEquals(agentB, workspace.currentFocus.agentId)
        assertEquals(ConversationId("b-exact"), workspace.currentFocus.conversationId)
        assertSame(openingB, port.opening)
        assertEquals(0, callbacks)
    }

    @Test
    fun sourceSwitchRejectsTheOldPagesCallback() {
        workspace.bindSource("backend-a", 1L)
        navigator.openNewChat(agent)
        val old = checkNotNull(port.opening)
        workspace.bindSource("backend-b", 2L)

        port.conversationShown(old, ConversationId("a-created"))

        assertEquals(ShellDestination.Home, workspace.page)
        assertNull(workspace.currentFocus.agentId)
        assertNull(workspace.currentFocus.conversationId)
        assertNull(port.openingFor(agent, null))
    }

    @Test
    fun unknownConversationNeverBorrowsTheFocusedAgent() {
        navigator.openAgent(agent)
        val before = workspace.currentFocus
        val opening = port.opening

        port.selectConversation(ConversationId("unknown"))

        assertEquals(before, workspace.currentFocus)
        assertSame(opening, port.opening)
    }

    @Test
    fun newChatWithoutARequestedAgentDoesNotChooseTheRosterHead() {
        port.startNewChat(null)
        assertNull(workspace.currentFocus.agentId)
        assertNull(port.opening)
    }

    @Test
    fun aLateResumeCannotReplaceAnExactConversationForTheSameAgent() {
        workspace.bindSource("backend-a", 1L)
        workspace.selectFocus(CurrentFocus("chat", agent))
        val resume = port.adopt(agent, null)
        port.open(agent, ConversationId("exact"))

        port.conversationShown(resume, ConversationId("resumed"))

        assertEquals(ConversationId("exact"), workspace.currentFocus.conversationId)
    }

    @Test
    fun unscopedLegacySelectionAndFavouriteCannotSeedTheActiveBackend() {
        val legacy = LastChatSelection(agentId = "agent-legacy", conversationId = "conversation-legacy")
        assertEquals(ShellDestination.Home, resolveStartDestination(true, null, legacy, "agent-favourite"))
    }

    @Test
    fun lateShownReportCannotMakeADeletedExactTargetAvailableAgain() {
        workspace.bindSource("source", 1L)
        workspace.navigate(ShellDestination.Chat(agent.value, "deleted"))
        val shown = port.adopt(agent, ConversationId("deleted"))
        workspace.reportAvailability(workspace.requestToken(), conversation = FocusAvailability.Unavailable)

        port.conversationShown(shown, ConversationId("deleted"))

        assertEquals(ConversationId("deleted"), workspace.currentFocus.conversationId)
        assertEquals(FocusAvailability.Unavailable, workspace.conversationAvailability)
        assertTrue("The selected unavailable tab remains", "deleted" in workspace.tabs.openConversationIds)
    }

    @Test
    fun anOldOpeningCannotDeliverItsCreateCallbackToARecreatedOwner() {
        workspace.bindSource("source", 1L)
        navigator.openNewChat(agent)
        var callbacks = 0
        port.createConversationForAgent(agent) { callbacks++ }
        val old = checkNotNull(port.opening)
        assertTrue(workspace.completeFocus(workspace.requestToken(), ConversationId("settled"), agent))
        val recreated = ShellWorkspaceState().apply {
            bindSource("source", 1L)
            assertTrue(restore(workspace.snapshot()))
        }
        val newPort = AndroidShellChatPort(recreated, { fleet }, { listOf(agent) })

        newPort.conversationShown(old, ConversationId("settled"))

        assertEquals(0, callbacks)
        assertEquals(ConversationId("settled"), recreated.currentFocus.conversationId)
    }

    @Test
    fun retiredPortCannotCompleteOrStartAnotherRequestAfterOwnerRecreation() {
        workspace.bindSource("source", 1L)
        var active = true
        val oldPort = AndroidShellChatPort(workspace, { fleet }, { listOf(agent) }, isCurrentOwner = { active })
        var callbacks = 0
        oldPort.createConversationForAgent(agent) { callbacks++ }
        val opening = checkNotNull(oldPort.opening)
        val recreated = ShellWorkspaceState().apply {
            bindSource("source", 1L)
            assertTrue(restore(workspace.snapshot()))
        }
        active = false

        oldPort.conversationShown(opening, ConversationId("late"))
        oldPort.open(agent, ConversationId("stale-navigation"))

        assertEquals(0, callbacks)
        assertNull(workspace.currentFocus.conversationId)
        assertNull(recreated.currentFocus.conversationId)
        assertEquals(CurrentFocusChatMode.New, recreated.currentFocus.chatMode)
    }

}
