package com.letta.mobile.ui.navigation

import com.letta.mobile.NotificationNavigationTarget
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.LastChatSelection
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.data.shell.ShellWorkspaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

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
        port.createConversationForAgent(agent) { created = it }
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
    fun theStartPageIsTheLaunchesChatThenTheLastThenTheFavouritesThenHome() {
        val notification = NotificationNavigationTarget("agent-n", "conv-n")
        val last = LastChatSelection(agentId = "agent-l", conversationId = "conv-l")
        assertEquals(ShellDestination.Settings(), resolveStartDestination(false, notification, last, "agent-f"))
        assertEquals(ShellDestination.Chat("agent-n", "conv-n"), resolveStartDestination(true, notification, last, "agent-f"))
        assertEquals(ShellDestination.Chat("agent-l", "conv-l"), resolveStartDestination(true, null, last, "agent-f"))
        assertEquals(ShellDestination.Chat("agent-f"), resolveStartDestination(true, null, null, "agent-f"))
        assertEquals(ShellDestination.Home, resolveStartDestination(true, null, null, null))
    }
}
