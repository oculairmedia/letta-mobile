@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.AgentPresence
import com.letta.mobile.ui.chat.surface.timeline.ChatTimelineTags
import com.letta.mobile.ui.mascot.FakeMascotShell
import kotlin.test.Test
import kotlinx.collections.immutable.persistentListOf

/**
 * letta-mobile-bglj6.1.21: on the phone, elapsed time and the running tool sit beside the
 * companion mascot. The timeline thinking row stays off whenever a mascot is present.
 */
class TouchCompanionStatusUiTest {
    @Test
    fun theCompanionShowsElapsedThinkingAndHidesTheTimelineRow() = runComposeUiTest {
        val port = CompanionTestPort()
        val shell = FakeMascotShell(COMPANION_TEST_AGENT)
        setTouchCompanionContent(port, shell)
        mainClock.advanceTimeBy(COMPANION_TEST_SETTLE_MILLIS)
        onNodeWithTag(ComposerTestTags.TOUCH_COMPANION_STATUS).assertExists()
        onNodeWithText("Thinking…", substring = true).assertExists()
        onNodeWithTag(ChatTimelineTags.THINKING).assertDoesNotExist()
    }

    @Test
    fun aRunningToolIsNamedBesideTheCompanion() = runComposeUiTest {
        val port = CompanionTestPort()
        port.uiState.value = port.uiState.value.copy(
            isStreaming = true,
            messages = persistentListOf(
                COMPANION_TEST_PROMPT,
                UiMessage(
                    id = "a1",
                    role = "assistant",
                    content = "",
                    timestamp = "2026-10-06T16:00:00Z",
                    runId = "run-1",
                    toolCalls = listOf(
                        UiToolCall(name = "Bash", arguments = "{}", result = null, status = "running"),
                    ),
                ),
            ),
        )
        val shell = FakeMascotShell(COMPANION_TEST_AGENT)
        setTouchCompanionContent(port, shell)
        mainClock.advanceTimeBy(COMPANION_TEST_SETTLE_MILLIS)
        onNode(
            hasTestTag(ComposerTestTags.TOUCH_COMPANION_STATUS) and
                hasAnyDescendant(hasText("Running Bash", substring = true)),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun presenceKeepsTheCompanionUpAfterStreamingFlagsDrop() = runComposeUiTest {
        val port = CompanionTestPort(typing = false)
        val shell = FakeMascotShell(COMPANION_TEST_AGENT)
        shell.registry.setPresence(COMPANION_TEST_AGENT, AgentPresence(activity = AgentActivityKind.WORKING, toolName = "grep"))
        setTouchCompanionContent(port, shell)
        mainClock.advanceTimeBy(COMPANION_TEST_SETTLE_MILLIS)
        onNodeWithTag(ComposerTestTags.TOUCH_COMPANION_STATUS).assertExists()
    }
}
