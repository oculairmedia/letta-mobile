package com.letta.mobile.data.shell

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.focus.CurrentFocus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Resume must never turn an explicit inbox/work view into a human-chat fallback. */
class ShellFocusHistoryTest {
    private val agent = AgentId("a")
    private val human = ShellConversationRef(ConversationId("human"), agent, "2026-11-01")

    @Test
    fun aKnownInboxCannotBecomeTheRememberedHumanChat() {
        val inbox = ShellConversationRef(ConversationId("inbox"), agent, "2026-11-07", listOf("lane:inbox"))
        val state = ShellWorkspaceState(ShellDestination.Chat(agent.value, inbox.id.value))
        state.conversationShown(inbox.id, agent)
        assertEquals(human.id, state.resolveAgentChat(agent, listOf(inbox, human)))
    }

    @Test
    fun anExactTargetWithoutHumanLaneEvidenceCannotBecomeAnAbsentRememberedChat() {
        val work = ConversationId("work")
        val state = ShellWorkspaceState(ShellDestination.Chat(agent.value, work.value))
        state.conversationShown(work, agent)
        assertEquals(human.id, state.resolveAgentChat(agent, listOf(human)))
    }

    @Test
    fun validatedHumanHistorySurvivesPartialAbsenceAndAnExplicitInboxVisit() {
        val state = ShellWorkspaceState(ShellDestination.Chat(agent.value, human.id.value))
        assertTrue(state.completeFocus(state.requestToken(), human.id, agent, human))
        val inbox = ShellConversationRef(ConversationId("inbox"), agent, "2026-11-07", listOf("lane:inbox"))
        state.navigate(ShellDestination.Chat(agent.value, inbox.id.value))
        assertTrue(state.completeFocus(state.requestToken(), inbox.id, agent, inbox))
        val newer = ShellConversationRef(ConversationId("newer"), agent, "2026-11-07")
        assertEquals(human.id, state.resolveAgentChat(agent, listOf(newer)))
    }

    @Test
    fun resumeRejectsNonHumanAndMismatchedOwnershipEvidenceAsAUnit() {
        val state = ShellWorkspaceState(ShellDestination.Chat(agent.value))
        val token = state.requestToken()
        val inbox = ShellConversationRef(ConversationId("inbox"), agent, "2026-11-07", listOf("lane:inbox"))
        assertFalse(state.completeFocus(token, inbox.id, agent, inbox))
        assertFalse(state.completeFocus(token, human.id, agent, human.copy(agent = AgentId("other"))))
        assertEquals(CurrentFocus("chat", agent), state.currentFocus)
        assertTrue(state.completeFocus(token, human.id, agent, human))
        assertEquals(human.id, state.selectedConversationId)
    }

}
