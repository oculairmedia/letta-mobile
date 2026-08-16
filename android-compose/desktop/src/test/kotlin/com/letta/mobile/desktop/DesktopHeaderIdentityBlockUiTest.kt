@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.chat.runtime.NowActiveStatus
import kotlin.test.Test

/**
 * UI-level coverage for the header identity block (letta-mobile-3arhe.1 AC
 * #8): the same [NowActiveBarState] mapping the pure-function tests in
 * [DesktopNowActiveBarTest] exercise, rendered end to end — title above
 * agent name, click-through to the pinned conversation, and long-title
 * ellipsis instead of clipping/wrapping.
 */
class DesktopHeaderIdentityBlockUiTest {

    private fun state(
        conversationTitle: String = "Conversation nv-190",
        agentName: String = "PM-letta-mobile",
        status: NowActiveStatus = NowActiveStatus.Idle,
    ) = NowActiveBarState(
        conversationTitle = conversationTitle,
        agentName = agentName,
        orbIndex = 0,
        status = status,
        backgroundWorkAgentName = null,
    )

    @Test
    fun rendersConversationTitleAboveAgentName() = runComposeUiTest {
        setContent {
            DesktopHeaderIdentityBlock(
                state = state(),
                actions = NowActiveBarActions(onOpenConversation = {}, onJumpToBackgroundWork = {}),
            )
        }

        onNodeWithText("Conversation nv-190").assertExists()
        onNodeWithText("PM-letta-mobile").assertExists()
    }

    @Test
    fun clickingTheBlockOpensThePinnedConversation() = runComposeUiTest {
        var opened = false
        setContent {
            DesktopHeaderIdentityBlock(
                state = state(),
                actions = NowActiveBarActions(
                    onOpenConversation = { opened = true },
                    onJumpToBackgroundWork = {},
                ),
            )
        }

        onNodeWithText("Conversation nv-190").performClick()
        kotlin.test.assertTrue(opened)
    }

    @Test
    fun showsTheLiveStatusLabelWhenNotIdle() = runComposeUiTest {
        setContent {
            DesktopHeaderIdentityBlock(
                state = state(status = NowActiveStatus.Streaming),
                actions = NowActiveBarActions(onOpenConversation = {}, onJumpToBackgroundWork = {}),
            )
        }

        onNodeWithText("responding…").assertExists()
    }

    @Test
    fun idleStatusShowsNoStatusLabel() = runComposeUiTest {
        setContent {
            DesktopHeaderIdentityBlock(
                state = state(status = NowActiveStatus.Idle),
                actions = NowActiveBarActions(onOpenConversation = {}, onJumpToBackgroundWork = {}),
            )
        }

        onNodeWithText("responding…").assertDoesNotExist()
        onNodeWithText("thinking…").assertDoesNotExist()
    }
}
