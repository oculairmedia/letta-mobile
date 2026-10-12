package com.letta.mobile.feature.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.feature.chat.screen.AgentPickerSheet
import com.letta.mobile.ui.theme.LettaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The header pill's agent switcher on the shared LettaPicker (hard cut S9). */
@RunWith(RobolectricTestRunner::class)
class AgentScaffoldAgentPickerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `AgentPickerSheet reports the picked agent and leaves closing to the caller`() {
        var selectedAgent: Agent? = null
        var dismissCalled = false
        val agent1 = Agent(id = AgentId("agent-1"), name = "Agent One", description = "First agent", createdAt = "2023-01-01T00:00:00Z", updatedAt = "2023-01-01T00:00:00Z")
        val agent2 = Agent(id = AgentId("agent-2"), name = "Agent Two", description = "Second agent", createdAt = "2023-01-01T00:00:00Z", updatedAt = "2023-01-01T00:00:00Z")

        composeRule.setContent {
            LettaTheme {
                AgentPickerSheet(
                    agents = listOf(agent1, agent2),
                    currentAgentId = "agent-1",
                    favoriteAgentId = null,
                    pinnedAgentIds = emptySet(),
                    onDismiss = { dismissCalled = true },
                    onAgentSelected = { selectedAgent = it },
                )
            }
        }

        composeRule.onNodeWithText("Agent Two").performClick()
        composeRule.waitForIdle()

        assertEquals(agent2, selectedAgent)
        // A pick never closes the picker by itself (LettaPicker): the scaffold hides it on select.
        assertFalse(dismissCalled)
    }
}
