@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.nowactive

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.chat.runtime.NowActiveStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-ckmub.3.14: the shared now-active identity and run controls the desktop header
 * draws (formerly DesktopHeaderIdentityBlock / DesktopHeaderTrailingControls).
 */
class NowActiveBarTest {
    private fun state(
        status: NowActiveStatus = NowActiveStatus.Idle,
        backgroundWorkAgentName: String? = null,
    ) = NowActiveBarState(
        conversationTitle = "Conversation nv-190",
        agentName = "PM-letta-mobile",
        agentId = "agent-1",
        status = status,
        backgroundWorkAgentName = backgroundWorkAgentName,
    )

    @Test
    fun identityShowsTheTitleOverTheAgentAndOpensThePinnedConversation() = runComposeUiTest {
        var opened = 0
        setContent {
            MaterialTheme {
                NowActiveIdentity(state(), NowActiveBarActions(onOpenConversation = { opened++ }, onJumpToBackgroundWork = {}))
            }
        }
        onNodeWithText("Conversation nv-190").assertExists()
        onNodeWithText("PM-letta-mobile").assertExists()
        onNodeWithTag(NowActiveBarTags.IDENTITY).performClick()
        runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun identityShowsTheLiveStatusOnlyWhenNotIdle() = runComposeUiTest {
        var status by mutableStateOf(NowActiveStatus.Streaming)
        setContent { MaterialTheme { NowActiveIdentity(state(status), NowActiveBarActions.None) } }
        onNodeWithText("responding…").assertExists()
        status = NowActiveStatus.Idle
        onNodeWithText("responding…").assertDoesNotExist()
        onNodeWithText("thinking…").assertDoesNotExist()
    }

    @Test
    fun theStopButtonShowsOnlyWhileARunIsLiveAndStopsIt() = runComposeUiTest {
        var status by mutableStateOf(NowActiveStatus.Thinking)
        var stops = 0
        val actions = NowActiveBarActions(onOpenConversation = {}, onJumpToBackgroundWork = {}, onStopRun = { stops++ })
        setContent { MaterialTheme { NowActiveRunControls(state(status), actions) } }
        onNodeWithTag(NowActiveBarTags.STOP).performClick()
        runOnIdle { assertEquals(1, stops) }
        status = NowActiveStatus.Idle
        onNodeWithTag(NowActiveBarTags.STOP).assertDoesNotExist()
    }

    @Test
    fun theBackgroundWorkChipNamesTheAgentAndJumpsToIt() = runComposeUiTest {
        var jumped = false
        val actions = NowActiveBarActions(onOpenConversation = {}, onJumpToBackgroundWork = { jumped = true })
        setContent { MaterialTheme { NowActiveRunControls(state(backgroundWorkAgentName = "Scout"), actions) } }
        onNodeWithText("Scout is working").assertExists()
        onNodeWithTag(NowActiveBarTags.BACKGROUND_WORK).performClick()
        runOnIdle { assertTrue(jumped) }
    }

    @Test
    fun liveRunCoversThinkingStreamingAndStopping() {
        assertTrue(NowActiveStatus.Thinking.isLiveRun)
        assertTrue(NowActiveStatus.Streaming.isLiveRun)
        assertTrue(NowActiveStatus.Stopping.isLiveRun)
        assertFalse(NowActiveStatus.Idle.isLiveRun)
        assertFalse(NowActiveStatus.Error.isLiveRun)
    }
}
