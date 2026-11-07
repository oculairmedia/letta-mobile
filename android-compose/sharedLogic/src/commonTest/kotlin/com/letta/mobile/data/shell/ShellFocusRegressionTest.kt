package com.letta.mobile.data.shell

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Behavioral regressions exercised through the pre-migration public facade. */
class ShellFocusRegressionTest {
    @Test
    fun choosingAnotherAgentClearsThePreviousConversationAtomically() {
        val state = ShellWorkspaceState(ShellDestination.Chat("a", "a1"))
        state.focusedAgentId = AgentId("b")
        assertNull(state.selectedConversationId)
        assertEquals("chat?agent=b", state.route)
        state.conversationShown(ConversationId("a2"), AgentId("a"))
        assertEquals("chat?agent=b", state.route)
    }

    @Test
    fun rosterAbsenceNeverChangesTheRequestedTarget() {
        val state = ShellWorkspaceState(ShellDestination.Chat("missing", "deleted"))
        state.rosterChanged(listOf(AgentId("other")))
        state.rosterChanged(emptyList())
        assertEquals(AgentId("missing"), state.focusedAgentId)
        assertEquals(ConversationId("deleted"), state.selectedConversationId)
        assertEquals(AgentId("missing"), state.focusFor(AgentId("other"), emptyList()))
    }

    @Test
    fun sourceSwitchGoesHomeAndKeepsPresentation() {
        val state = ShellWorkspaceState(ShellDestination.Memory("a"))
        state.railExpanded = true
        state.sessionChanged("one")
        state.sessionChanged("two")
        assertEquals(ShellDestination.Home, state.page)
        assertNull(state.focusedAgentId)
        assertEquals(true, state.railExpanded)
    }

    @Test
    fun anUnscopedDefaultTabCannotBorrowTheClosedTabsAgent() {
        val state = ShellWorkspaceState(ShellDestination.Chat("a", "a1"))
        state.tabs = com.letta.mobile.data.desktopshell.ConversationTabsState(listOf("default", "a1"))
        state.closeConversationTab(ConversationId("a1"))
        assertEquals(ShellDestination.Home, state.page)
        assertNull(state.selectedConversationId)
        assertEquals(listOf("default"), state.tabs.openConversationIds)
    }

    @Test
    fun openingAnotherAgentsEditorKeepsTheChatUnderneath() {
        val state = ShellWorkspaceState(ShellDestination.Chat("a", "a1"))
        state.navigate(ShellDestination.AgentEditor("b"))
        assertEquals(AgentId("a"), state.focusedAgentId)
        assertEquals(ConversationId("a1"), state.selectedConversationId)
        state.editAgentId = null
        assertEquals("chat?agent=a&conversation=a1", state.route)
    }
}
