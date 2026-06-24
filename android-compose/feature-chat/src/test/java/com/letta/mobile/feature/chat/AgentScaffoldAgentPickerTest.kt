package com.letta.mobile.feature.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.feature.chat.screen.AgentPickerSheet
import com.letta.mobile.ui.theme.LettaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentScaffoldAgentPickerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `AgentPickerSheet selects agent and unlatches gate on dismiss`() {
        var selectedAgent: Agent? = null
        var dismissCalled = false
        // Need to provide Instant parseable string or mock
        // Since we don't strictly need them here, we'll pass null or use a valid string if the data class needs it
        // The compile error was expecting a String? for createdAt and updatedAt instead of Instant!
        val agent1 = Agent(id = AgentId("agent-1"), name = "Agent One", description = "First agent", createdAt = "2023-01-01T00:00:00Z", updatedAt = "2023-01-01T00:00:00Z")
        val agent2 = Agent(id = AgentId("agent-2"), name = "Agent Two", description = "Second agent", createdAt = "2023-01-01T00:00:00Z", updatedAt = "2023-01-01T00:00:00Z")
        val agents = listOf(agent1, agent2)

        composeRule.setContent {
            LettaTheme {
                AgentPickerSheet(
                    agents = agents,
                    currentAgentId = "agent-1",
                    favoriteAgentId = null,
                    pinnedAgentIds = emptySet(),
                    onDismiss = { dismissCalled = true },
                    onTogglePinned = {},
                    onAgentSelected = { selectedAgent = it },
                )
            }
        }

        // Tap on Agent Two
        composeRule.onNodeWithText("Agent Two").performClick()

        // Wait for bottom sheet to hide and invoke onCompletion
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.waitForIdle()

        assertEquals(agent2, selectedAgent)
        assertTrue(dismissCalled)
    }
}
