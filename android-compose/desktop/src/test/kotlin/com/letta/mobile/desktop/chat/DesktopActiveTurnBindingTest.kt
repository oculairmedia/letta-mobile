package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.AgentId

import com.letta.mobile.data.model.ConversationId

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopActiveTurnBindingTest {
    @Test
    fun ownerGatesPreTokenDetailWithoutMakingItBusyAuthority() = runTest {
        val conversation = ConversationId("conversation")
        val agent = AgentId("agent")
        val detail = kotlinx.coroutines.flow.MutableStateFlow<String?>(conversation.value)
        val session = com.letta.mobile.data.presence.ActiveTurnSession(backgroundScope)
        val binding = DesktopActiveTurnBinding(backgroundScope, null, detail) {}
        binding.bindSessions(kotlinx.coroutines.flow.MutableStateFlow(session))
        val owner = session.owner(conversation, agent)
        val turn = com.letta.mobile.data.presence.ActiveTurnIdentity("run", "turn")
        assertNull(binding.thinkingConversationId.value)
        binding.start(conversation, agent, turn)
        assertEquals(conversation.value, binding.thinkingConversationId.value)
        detail.value = null
        assertNull(binding.thinkingConversationId.value)
        assertEquals(com.letta.mobile.data.presence.ActiveTurnPhase.Thinking, owner.state.value.phase)
        detail.value = conversation.value
        binding.terminate(conversation, turn, com.letta.mobile.data.presence.ActiveTurnTerminalKind.Finished)
        assertNull(binding.thinkingConversationId.value)
        binding.close()
        session.close()
    }

    @Test
    fun ownerCannotBeReboundToAnotherAgent() = runTest {
        val binding = DesktopActiveTurnBinding(backgroundScope) {}
        binding.owner(ConversationId("conversation"), AgentId("agent-a"))
        assertFailsWith<IllegalArgumentException> { binding.owner(ConversationId("conversation"), AgentId("agent-b")) }
        binding.close()
    }
}
