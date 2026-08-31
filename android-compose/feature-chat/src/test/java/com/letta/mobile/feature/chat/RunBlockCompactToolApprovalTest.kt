package com.letta.mobile.feature.chat

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.letta.mobile.feature.chat.screen.RunBlock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class RunBlockCompactToolApprovalTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactedRunToolCallsRenderUserInputApproval() {
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    RunBlock(
                        messages = listOf(
                            userInputToolMessage(
                                id = "tc-a",
                                approvalRequest = approvalRequest(),
                            ),
                            toolMessage(id = "tc-b", command = "ls"),
                        ),
                        collapsed = false,
                        onToggleCollapsed = {},
                        activeApprovalRequestId = null,
                        onApprovalDecision = { _, _, _, _ -> },
                    ) { message, _, rowModifier ->
                        Text(text = message.id, modifier = rowModifier)
                    }
                }
            }
        }

        // letta-mobile: TIMELINE_V1 is the only tool-call rendering path now — the
        // legacy CompactToolCallGroupCard's "N tool calls" header no longer exists.
        // The projected timeline renders one row per call instead.
        composeRule.onNodeWithText("Bash(ls)").assertIsDisplayed()
        composeRule.onNodeWithText("The agent has a question").assertIsDisplayed()
        composeRule.onNodeWithText("Continue?").assertIsDisplayed()
        // The compact grouping contract is rendering-only; callback transport is
        // covered by ProjectedToolTimelineTest's direct projected group fixture.
    }

    private fun approvalRequest() = UiApprovalRequest(
        requestId = "approval-1",
        toolCalls = listOf(
            UiApprovalToolCall(
                toolCallId = "call-a",
                name = "AskUserQuestion",
                arguments = """{"questions":[{"question":"Continue?","options":[{"label":"Yes"}]}]}""",
            ),
            UiApprovalToolCall(
                toolCallId = "call-b",
                name = "Bash",
                arguments = """{"command":"ls"}""",
            ),
        ),
    )

    private fun userInputToolMessage(
        id: String,
        approvalRequest: UiApprovalRequest,
    ) = UiMessage(
        id = id,
        role = "assistant",
        content = "",
        timestamp = "2026-05-09T00:00:00Z",
        runId = "run-1",
        approvalRequest = approvalRequest,
        toolCalls = listOf(
            UiToolCall(
                name = "AskUserQuestion",
                arguments = """{"questions":[{"question":"Continue?","options":[{"label":"Yes"}]}]}""",
                result = null,
                toolCallId = "call-a",
            ),
        ),
    )

    private fun toolMessage(
        id: String,
        command: String,
        approvalRequest: UiApprovalRequest? = null,
    ) = UiMessage(
        id = id,
        role = "assistant",
        content = "",
        timestamp = "2026-05-09T00:00:00Z",
        runId = "run-1",
        approvalRequest = approvalRequest,
        toolCalls = listOf(
            UiToolCall(
                name = "Bash",
                arguments = """{"command":"$command"}""",
                result = null,
                toolCallId = if (id == "tc-a") "call-a" else "call-b",
            )
        ),
    )
}
