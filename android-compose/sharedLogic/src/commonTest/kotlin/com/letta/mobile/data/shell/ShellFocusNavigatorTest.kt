package com.letta.mobile.data.shell

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.focus.CurrentFocus
import com.letta.mobile.data.shell.focus.CurrentFocusChatMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Effect-boundary races: the fake enforces known conversations/owners before selecting or sending. */
class ShellFocusNavigatorTest {
    private val a = AgentId("a")
    private val b = AgentId("b")
    private val a1 = ConversationId("a1")
    private val b2 = ConversationId("b2")

    @Test
    fun laterSelectionWinsOverDeferredHomeCreationAndSuppressesItsSend() {
        val state = workspace()
        val chat = DeferredChat()
        val navigator = ShellWorkspaceNavigator(state, chat)
        navigator.submitHomePrompt(ShellHomePrompt("hello", a, null))
        assertEquals(CurrentFocus("chat", a, chatMode = CurrentFocusChatMode.New), state.currentFocus)
        chat.all = listOf(ShellConversationRef(b2, b, "2026-11-07"))
        navigator.openConversation(b2)
        chat.finishCreate(a1)
        assertEquals(CurrentFocus("chat", b, b2, CurrentFocusChatMode.Exact), state.currentFocus)
        assertTrue(chat.sent.isEmpty())
        assertEquals(listOf(b2), chat.selected)
    }

    @Test
    fun oldGenerationCreationCannotSelectOrSendEvenIfRawAgentIdsMatch() {
        val state = workspace()
        val chat = DeferredChat()
        val navigator = ShellWorkspaceNavigator(state, chat)
        navigator.submitHomePrompt(ShellHomePrompt("hello", a, null))
        state.bindSource("other", 1)
        navigator.openAgent(a)
        chat.finishCreate(a1)
        assertEquals(CurrentFocus("chat", a), state.currentFocus)
        assertTrue(chat.sent.isEmpty())
        assertTrue(chat.selected.isEmpty())
    }

    @Test
    fun currentHomeCreationCommitsBeforeSendAndOnlyOnce() {
        val state = workspace()
        val chat = DeferredChat()
        val navigator = ShellWorkspaceNavigator(state, chat)
        navigator.submitHomePrompt(ShellHomePrompt("hello", a, null))
        chat.onSend = { assertEquals(a1, state.currentFocus.conversationId) }
        chat.finishCreate(a1)
        chat.finishCreate(a1)
        assertEquals(listOf(a1 to "hello"), chat.sent)
        assertEquals(CurrentFocusChatMode.Exact, state.currentFocus.chatMode)
    }

    @Test
    fun sendReadsTheOwnerAndUnavailableTargetsCannotSend() {
        val state = workspace()
        val chat = DeferredChat()
        chat.all = listOf(ShellConversationRef(a1, a, "2026-11-07"), ShellConversationRef(b2, b, "2026-11-07"))
        val navigator = ShellWorkspaceNavigator(state, chat)
        navigator.openConversation(a1)
        chat.selectedConversation = b2
        navigator.sendToSelectedConversation("owner")
        assertEquals(listOf(a1 to "owner"), chat.sent)
        state.reportAvailability(state.requestToken(), conversation = FocusAvailability.Unavailable)
        navigator.sendToSelectedConversation("blocked")
        assertEquals(listOf(a1 to "owner"), chat.sent)
    }

    @Test
    fun unknownConversationDoesNotActivateUsingPreviousFocusedAgent() {
        val state = workspace()
        state.focusedAgentId = a
        val chat = DeferredChat()
        val navigator = ShellWorkspaceNavigator(state, chat)
        navigator.openConversation(b2)
        assertNull(state.currentFocus.agentId)
        assertEquals(b2, state.selectedConversationId)
        assertTrue(chat.selected.isEmpty())
        assertFalse(state.completeFocus(state.requestToken(), a1, a))
    }

    @Test
    fun homeSendNeverSubstitutesAnotherAgentWhenTheChosenAgentHasNoLoadedChat() {
        val state = workspace()
        val chat = DeferredChat()
        chat.all = listOf(ShellConversationRef(b2, b, "2026-11-07"))
        ShellWorkspaceNavigator(state, chat).submitHomePrompt(ShellHomePrompt("hello", a, b))
        assertEquals(CurrentFocus("chat", a, chatMode = CurrentFocusChatMode.New), state.currentFocus)
        assertTrue(chat.sent.isEmpty())
        assertTrue(chat.selected.isEmpty())
    }

    @Test
    fun aTokenlessResumeReportCannotReplaceASelectionOrInventItsRequestIdentity() {
        val state = workspace()
        state.selectFocus(CurrentFocus("chat", b))
        val request = state.requestToken()
        state.conversationShown(b2, b)
        assertEquals(CurrentFocus("chat", b), state.currentFocus)
        assertTrue(state.conversationShown(request, b2, b))
        assertEquals(b2, state.selectedConversationId)
    }

    @Test
    fun lateSavedStateCannotOverrideAnExplicitSelection() {
        val first = workspace()
        first.selectFocus(CurrentFocus("chat", a, a1, CurrentFocusChatMode.Exact))
        val saved = first.snapshot()
        val state = workspace()
        state.selectFocus(CurrentFocus("chat", b, b2, CurrentFocusChatMode.Exact))
        assertFalse(state.restore(saved))
        assertEquals(b2, state.selectedConversationId)
    }

    private fun workspace(): ShellWorkspaceState = ShellWorkspaceState().apply { bindSource("source", 1) }

    private class DeferredChat : ShellChatPort {
        var all: List<ShellConversationRef> = emptyList()
        override var selectedConversation: ConversationId? = null
        val selected = mutableListOf<ConversationId>()
        val sent = mutableListOf<Pair<ConversationId, String>>()
        var onSend: () -> Unit = {}
        private var creatingAgent: AgentId? = null
        private var onCreated: ((ConversationId) -> Unit)? = null

        override fun conversations(): List<ShellConversationRef> = all
        override fun selectConversation(conversation: ConversationId) {
            require(all.any { it.id == conversation && it.agent != null })
            selected += conversation
            selectedConversation = conversation
        }
        override fun createConversationForAgent(agent: AgentId, onCreated: (ConversationId) -> Unit) {
            creatingAgent = agent
            this.onCreated = onCreated
        }
        override fun startNewChat(agent: AgentId?) { requireNotNull(agent) }
        override fun send(conversation: ConversationId, text: String) {
            require(all.any { it.id == conversation && it.agent != null && it.lane.canTarget })
            onSend()
            sent += conversation to text
        }
        override fun stageComposerText(text: String) { require(text.isNotBlank()) }

        fun finishCreate(id: ConversationId) {
            val agent = checkNotNull(creatingAgent)
            all = all.filterNot { it.id == id } + ShellConversationRef(id, agent, "2026-11-07")
            checkNotNull(onCreated)(id)
        }
    }
}
