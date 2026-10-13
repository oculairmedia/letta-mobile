@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.palette

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.ui.components.picker.LettaPickerTags
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-ckmub.3.8 (S8b): New conversation on the shared LettaPicker (was desktop's own surface). */
class NewConversationPickerTest {
    private fun agent(id: String, name: String) = NewConversationAgent(id = id, name = name, subtitle = null, orbIndex = 0)

    @Test
    fun buildsAgentsWithRosterSubtitleAndAvatarOverride() {
        val rail = listOf("a1" to "Meridian", "a2" to "Scout")
        val roster = listOf(
            Agent(id = AgentId("a1"), name = "Meridian", description = "Ops copilot", model = "gpt-x"),
            Agent(id = AgentId("a2"), name = "Scout", model = "haiku"),
        )
        val agents = newConversationAgents(rail, roster, avatarStyleByAgentId = mapOf("a2" to 7))

        assertEquals("Ops copilot", agents[0].subtitle)
        assertEquals(0, agents[0].orbIndex)
        assertEquals("haiku", agents[1].subtitle)
        assertEquals(7, agents[1].orbIndex)
    }

    @Test
    fun recentLeadsThenLetterSectionsWithNonLettersLast() {
        val agents = listOf(agent("1", "zeta"), agent("2", "Alpha"), agent("3", "42-bot"), agent("4", "apex"))
        val sections = newConversationSections(NewConversationDirectory(agents, recentLimit = 2))

        assertEquals(listOf("Recent", "A", "Z", "#"), sections.map { it.title })
        assertEquals(listOf("zeta", "Alpha"), sections[0].items.map { it.title })
        assertEquals(false, sections[0].showWhileSearching)
        assertEquals(listOf("Alpha", "apex"), sections[1].items.map { it.title })
        assertEquals(listOf("42-bot"), sections[3].items.map { it.title })
    }

    @Test
    fun typingNarrowsTheDirectoryAndAPickClosesThenOpensTheAgent() = runComposeUiTest {
        val events = mutableListOf<String>()
        val directory = NewConversationDirectory(listOf(agent("a", "Meridian"), agent("b", "Scout"), agent("c", "merlin")), recentLimit = 0)
        val actions = NewConversationActions(
            onAgentSelected = { events += "open:$it" },
            onCreateAgent = { events += "create" },
            onDismiss = { events += "dismiss" },
        )
        setContent { MaterialTheme { ProvideLettaHost(LettaHost.Pointer) { NewConversationPicker(directory, actions) } } }

        onNodeWithTag(LettaPickerTags.SEARCH).performTextInput("mer")
        onNodeWithText("Scout").assertDoesNotExist()
        onNodeWithText("merlin").assertExists()
        onNodeWithText("merlin").performClick()
        onNodeWithTag(NewConversationPickerTags.CREATE_AGENT).performClick()
        onNodeWithTag(NewConversationPickerTags.NEW_CANVAS).assertDoesNotExist()

        assertEquals(listOf("dismiss", "open:c", "dismiss", "create"), events)
    }
}
