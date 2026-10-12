@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.notify

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.notify.AttentionPrefs
import com.letta.mobile.data.notify.AttentionPrefsRepository
import com.letta.mobile.ui.context.AgentContextCardActions
import com.letta.mobile.ui.context.AgentContextCardBinding
import com.letta.mobile.ui.context.AgentContextCardDeps
import com.letta.mobile.ui.context.AgentContextCardHost
import com.letta.mobile.ui.context.AgentContextFocus
import androidx.compose.runtime.CompositionLocalProvider
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.context.AgentContextTags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** letta-mobile-bzvro.27 (F27): the per-agent mute switch and the global approvals switch. */
class AgentNotificationsSectionUiTest {
    private class FakePrefs(initial: AttentionPrefs = AttentionPrefs()) : AttentionPrefsRepository {
        override val prefs = MutableStateFlow(initial)

        override fun setAgentMuted(agentId: String, muted: Boolean) {
            prefs.update { it.copy(mutedAgentIds = if (muted) it.mutedAgentIds + agentId else it.mutedAgentIds - agentId) }
        }

        override fun setApprovalNotificationsEnabled(enabled: Boolean) {
            prefs.update { it.copy(approvalNotificationsEnabled = enabled) }
        }
    }

    @Test
    fun theSwitchMutesAndUnmutesOnlyThisAgent() = runComposeUiTest {
        val prefs = FakePrefs(AttentionPrefs(mutedAgentIds = setOf("agent-2")))
        setContent { MaterialTheme { AgentNotificationsSection(prefs, agentId = "agent-1") } }

        onNodeWithTag(AgentNotificationsTags.SWITCH).assertIsOn()
        onNodeWithTag(AgentNotificationsTags.CAPTION).assertTextEquals(AgentNotificationsStrings.ON_CAPTION)

        onNodeWithTag(AgentNotificationsTags.SWITCH).performClick()
        assertEquals(setOf("agent-1", "agent-2"), prefs.prefs.value.mutedAgentIds)
        onNodeWithTag(AgentNotificationsTags.SWITCH).assertIsOff()
        onNodeWithTag(AgentNotificationsTags.CAPTION).assertTextEquals(AgentNotificationsStrings.MUTED_CAPTION)

        onNodeWithTag(AgentNotificationsTags.SWITCH).performClick()
        assertEquals(setOf("agent-2"), prefs.prefs.value.mutedAgentIds)
    }

    @Test
    fun theCaptionSaysWhenApprovalsAreOffEverywhere() = runComposeUiTest {
        val prefs = FakePrefs(AttentionPrefs(approvalNotificationsEnabled = false))
        setContent { MaterialTheme { AgentNotificationsSection(prefs, agentId = "agent-1") } }
        onNodeWithTag(AgentNotificationsTags.CAPTION).assertTextEquals(AgentNotificationsStrings.APPROVALS_OFF_CAPTION)
    }

    @Test
    fun theGlobalSwitchTurnsApprovalNotificationsOff() = runComposeUiTest {
        val prefs = FakePrefs()
        setContent { MaterialTheme { ApprovalNotificationsSwitch(prefs) } }
        onNodeWithTag(AgentNotificationsTags.APPROVALS_SWITCH).assertIsOn().performClick()
        assertFalse(prefs.prefs.value.approvalNotificationsEnabled)
        onNodeWithTag(AgentNotificationsTags.APPROVALS_SWITCH).assertIsOff().performClick()
        assertTrue(prefs.prefs.value.approvalNotificationsEnabled)
    }

    @Test
    fun theDrawerCardSheetCarriesTheSwitchWhenTheHostPostsNotifications() = runComposeUiTest {
        val prefs = FakePrefs()
        val deps = AgentContextCardDeps(readings = null, breakdown = null, compaction = null, pickerSource = null, attention = prefs)
        val focus = AgentContextFocus(
            agentId = "agent-1",
            conversationId = null,
            modelLabel = "Claude Opus",
            modelValue = null,
            effort = null,
            windowTokens = null,
            turnRunning = false,
        )
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLettaHost provides LettaHost.Pointer) {
                    AgentContextCardHost(AgentContextCardBinding(deps, AgentContextCardActions(onModelSelected = {})), focus)
                }
            }
        }
        onNodeWithTag(AgentContextTags.CARD).performClick()
        onNodeWithTag(AgentNotificationsTags.SWITCH).performClick()
        assertEquals(setOf("agent-1"), prefs.prefs.value.mutedAgentIds)
    }
}
