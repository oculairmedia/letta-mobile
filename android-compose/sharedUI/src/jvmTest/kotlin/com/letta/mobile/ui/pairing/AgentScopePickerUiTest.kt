@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.pairing

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.pairing.PairInviteRequest
import com.letta.mobile.data.pairing.PairInviteUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Choose agents" as a search picker: filtering, chosen chips that stay visible, and the same grant. */
class AgentScopePickerUiTest {
    private val agents = listOf(
        AgentChoice("a1", "Research assistant"),
        AgentChoice("a2", "Travel planner"),
        AgentChoice("a3", "Research librarian"),
    )

    @Composable
    private fun Panel(onMint: (PairInviteRequest) -> Unit) {
        MaterialTheme {
            PairDevicePanel(
                PairDeviceView(PairInviteUiState(), agents, 0),
                PairDeviceActions(onMint = onMint, onRevokeDevice = {}, onRevokeInvite = {}),
            )
        }
    }

    @Test
    fun typingFiltersTheListAndAChosenAgentStaysVisibleAsAChip() = runComposeUiTest {
        var minted: PairInviteRequest? = null
        setContent { Panel { minted = it } }
        onNodeWithTag(PairingTags.INVITE_AGENT_SEARCH).performTextInput("travel")
        onNodeWithTag(PairingTags.agentOption("a1")).assertDoesNotExist()
        onNodeWithTag(PairingTags.agentOption("a2")).assertIsOff().performClick()
        onNodeWithTag(PairingTags.agentOption("a2")).assertIsOn()
        onNodeWithTag(PairingTags.INVITE_AGENT_SEARCH).performTextInput("zzz")
        onNodeWithTag(PairingTags.INVITE_AGENT_NO_MATCH).assertIsDisplayed()
        // Filtered out of the list, the chosen agent is still shown.
        onNodeWithTag(PairingTags.chosenAgent("a2")).assertIsDisplayed()
        onNodeWithTag(PairingTags.INVITE_SHOW).performClick()
        assertEquals(setOf("a2"), minted?.agentIds)
    }

    @Test
    fun tappingAChipRemovesTheAgent() = runComposeUiTest {
        setContent { Panel {} }
        onNodeWithTag(PairingTags.agentOption("a1")).performClick()
        onNodeWithTag(PairingTags.agentOption("a3")).performClick()
        onNodeWithTag(PairingTags.chosenAgent("a1")).performClick()
        onNodeWithTag(PairingTags.chosenAgent("a1")).assertDoesNotExist()
        onNodeWithTag(PairingTags.agentOption("a1")).assertIsOff()
        onNodeWithTag(PairingTags.chosenAgent("a3")).assertIsDisplayed()
    }

    @Test
    fun everyTypedWordMustAppearInTheName() {
        assertTrue(agentMatches("", "Research assistant"))
        assertTrue(agentMatches("res ASSIST", "Research assistant"))
        assertFalse(agentMatches("research planner", "Research assistant"))
    }
}
