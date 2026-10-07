package com.letta.mobile.ui.chat.surface.composer

import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.AgentPresence
import com.letta.mobile.ui.chat.render.ChatUiState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** letta-mobile-bglj6.1.21: when the Touch companion stands above the bar. */
class CompanionWorkingTest {
    @Test
    fun streamingOrTypingIsWorking() {
        assertTrue(companionWorking(ChatUiState(isStreaming = true), presence = null))
        assertTrue(companionWorking(ChatUiState(isAgentTyping = true), presence = null))
        assertFalse(companionWorking(ChatUiState(), presence = null))
    }

    @Test
    fun anA2uiDelayLineKeepsTheCompanionUp() {
        assertTrue(companionWorking(ChatUiState(a2uiThinkingDelayMessage = "Still working on this…"), presence = null))
        assertFalse(companionWorking(ChatUiState(a2uiThinkingDelayMessage = "  "), presence = null))
    }

    @Test
    fun anyLivePresenceIsWorkingEvenWithoutStreamingFlags() {
        assertTrue(companionWorking(ChatUiState(), AgentPresence(activity = AgentActivityKind.WORKING, toolName = "Bash")))
        assertTrue(companionWorking(ChatUiState(), AgentPresence(activity = AgentActivityKind.THINKING)))
        assertTrue(companionWorking(ChatUiState(), AgentPresence(activity = AgentActivityKind.SPEAKING)))
        assertFalse(companionWorking(ChatUiState(), AgentPresence.IDLE))
    }
}
